/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.focusable
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.applyShortcutPreset
import com.reverie.paint.core.exportShortcutScheme
import com.reverie.paint.core.findShortcutConflictsFor
import com.reverie.paint.core.getShortcutKeys
import com.reverie.paint.core.importShortcutScheme
import com.reverie.paint.core.keyEventToString
import com.reverie.paint.core.resetShortcutBinding
import com.reverie.paint.core.resetShortcuts
import com.reverie.paint.core.setShortcutKeys
import com.reverie.paint.core.unbindShortcut
import com.reverie.paint.core.updateShortcutAutoRepeat
import com.reverie.paint.core.updateShortcutHoldTemporaryTools
import com.reverie.paint.model.ALL_SHORTCUT_DEFINITIONS
import com.reverie.paint.model.MAX_SHORTCUT_KEYS_PER_ACTION
import com.reverie.paint.model.ShortcutCategory
import com.reverie.paint.model.ShortcutDefinition
import com.reverie.paint.model.ShortcutPreset
import com.reverie.paint.model.findConflictingShortcutIds
import com.reverie.paint.model.findShortcutDefinition
import com.reverie.paint.model.isShortcutCustomized
import com.reverie.paint.model.normalizeShortcut
import com.reverie.paint.model.resolveShortcutKeys
import com.reverie.paint.model.shortcutMatchesQuery
import com.reverie.paint.model.shortcutPresetOverrides
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Morandi

/** 待确认的冲突：动作、期望按键、被占用的其他动作。 */
private data class ShortcutConflictRequest(
    val defId: String,
    val keys: List<String>,
    val conflicts: List<ShortcutDefinition>,
)

private val BlockShape = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(9.dp)

private enum class CapsuleState { BOUND, EMPTY, CONFLICT }

/**
 * 快捷键设置页（对标 Krita「配置 Krita → 快捷键」）。
 *
 * 结构: 搜索 → 分类 → 方案 → 分组动作列表 → 全局选项。
 * 每个动作可挂主按键 + 备选按键；点名冲突时先弹确认再替换。
 * 视觉上全部用"色块分层"，不使用描边，保证长列表在窄面板里也不发花。
 */
@Composable
internal fun ShortcutSettingsPage(
    vm: PaintViewModel,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var activeCategory by remember { mutableStateOf<ShortcutCategory?>(ShortcutCategory.PAINTING) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var conflictRequest by remember { mutableStateOf<ShortcutConflictRequest?>(null) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showSchemeDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }

    // 搜索框输入期间必须让全局快捷键让路，否则打得进去的字母会先被工具切换吃掉
    if (searchFocused) TextInputGuard(vm)

    val bindings = vm.shortcutBindings
    val conflictIds = remember(bindings) { findConflictingShortcutIds(bindings) }
    val customizedCount = remember(bindings) {
        ALL_SHORTCUT_DEFINITIONS.count { isShortcutCustomized(bindings, it) }
    }
    val activePreset = remember(bindings) {
        ShortcutPreset.entries.firstOrNull { shortcutPresetOverrides(it) == bindings }
    }

    val visibleDefs = remember(query, activeCategory, bindings, vm) {
        ALL_SHORTCUT_DEFINITIONS.filter { def ->
            (activeCategory == null || def.category == activeCategory) &&
                shortcutMatchesQuery(
                    def,
                    // 用 VM 的 Application context 取名，避免在组合期查 LocalContext 资源
                    vm.getString(def.nameRes),
                    resolveShortcutKeys(bindings, def),
                    query,
                )
        }
    }
    // 换分类/改搜索词时回到列表顶部 (新 ScrollState 即回到 0)
    val scrollState = remember(activeCategory, query) { ScrollState(0) }

    // 顶部搜索/分类/方案固定，只让动作列表滚动：147 条的长列表翻到中段时
    // 仍能直接换分类或搜关键词，不会先把筛选条滚丢
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShortcutPageHeader(
                title = stringResource(R.string.settings_shortcuts_title),
                onBack = onBack,
                onReset = { showResetConfirm = true },
            )

            Text(
                text = stringResource(R.string.shortcut_total_count, ALL_SHORTCUT_DEFINITIONS.size) +
                    " · " + stringResource(R.string.shortcut_custom_count, customizedCount) +
                    if (conflictIds.isEmpty()) "" else " · " + stringResource(R.string.shortcut_conflict_count, conflictIds.size),
                color = if (conflictIds.isEmpty()) Morandi.subText else Morandi.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )

            ShortcutSearchField(
                query = query,
                onQueryChange = { query = it },
                onFocusChanged = { searchFocused = it },
            )

            ShortcutCategoryBar(
                activeCategory = activeCategory,
                onSelect = { activeCategory = it },
            )

            ShortcutSchemeRow(
                presetLabel = activePreset?.let { stringResource(it.labelRes) }
                    ?: stringResource(R.string.shortcut_preset_custom),
                onOpenPresets = { showPresetPicker = true },
                onOpenScheme = { showSchemeDialog = true },
            )

            Spacer(Modifier.height(2.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            if (visibleDefs.isEmpty()) {
                Text(
                    stringResource(R.string.shortcut_search_empty),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BlockShape)
                        .background(Morandi.panelHi.copy(alpha = 0.38f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    var lastSection: String? = null
                    var lastCategory: ShortcutCategory? = null
                    visibleDefs.forEach { def ->
                        val sectionKey = def.section.id
                        if (sectionKey != lastSection) {
                            if (activeCategory == null && def.category != lastCategory) {
                                Text(
                                    stringResource(def.category.titleRes),
                                    color = Morandi.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                                )
                            }
                            Text(
                                stringResource(def.section.titleRes),
                                color = Morandi.subText.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
                            )
                            lastSection = sectionKey
                            lastCategory = def.category
                        }
                        ShortcutRow(
                            title = stringResource(def.nameRes),
                            keys = resolveShortcutKeys(bindings, def),
                            customized = isShortcutCustomized(bindings, def),
                            hasConflict = def.id in conflictIds,
                            onRecord = { recordingId = def.id },
                            onReset = { vm.resetShortcutBinding(def.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            ShortcutGlobalOptions(
                autoRepeat = vm.shortcutAutoRepeatEnabled,
                onAutoRepeatChange = { vm.updateShortcutAutoRepeat(it) },
                holdTemporary = vm.shortcutHoldTemporaryTools,
                onHoldTemporaryChange = { vm.updateShortcutHoldTemporaryTools(it) },
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    // ---- 按键录制 ----
    recordingId?.let { id ->
        findShortcutDefinition(id)?.let { def ->
            ShortcutRecordDialog(
                vm = vm,
                def = def,
                onDismiss = { recordingId = null },
                onSave = { keys ->
                    val conflicts = vm.findShortcutConflictsFor(def.id, keys)
                    if (conflicts.isEmpty()) {
                        vm.setShortcutKeys(def.id, keys)
                        recordingId = null
                    } else {
                        conflictRequest = ShortcutConflictRequest(def.id, keys, conflicts)
                        recordingId = null
                    }
                },
            )
        }
    }

    // ---- 冲突确认 ----
    conflictRequest?.let { request ->
        ShortcutConflictDialog(
            request = request,
            onCancel = { conflictRequest = null },
            onReplace = {
                request.conflicts.forEach { vm.unbindShortcut(it.id) }
                vm.setShortcutKeys(request.defId, request.keys)
                conflictRequest = null
            },
        )
    }

    // ---- 方案选择 ----
    if (showPresetPicker) {
        ShortcutPresetDialog(
            activePreset = activePreset,
            onDismiss = { showPresetPicker = false },
            onPick = { preset ->
                vm.applyShortcutPreset(preset)
                showPresetPicker = false
            },
        )
    }

    // ---- 导入导出 ----
    if (showSchemeDialog) {
        ShortcutSchemeDialog(
            vm = vm,
            exported = remember { vm.exportShortcutScheme() },
            onDismiss = { showSchemeDialog = false },
        )
    }

    // ---- 重置确认 ----
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = {
                Text(
                    stringResource(R.string.shortcut_reset_confirm_title),
                    color = Morandi.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.shortcut_reset_confirm_desc),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                DialogActionText(
                    text = stringResource(R.string.settings_shortcut_reset),
                    color = Morandi.accent,
                    bold = true,
                ) {
                    vm.resetShortcuts()
                    showResetConfirm = false
                }
            },
            dismissButton = {
                DialogActionText(
                    text = stringResource(R.string.common_cancel),
                    color = Morandi.subText,
                ) { showResetConfirm = false }
            },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(16.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------

@Composable
private fun ShortcutPageHeader(
    title: String,
    onBack: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onBack() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.common_back),
                tint = Morandi.text,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(R.string.settings_shortcut_reset),
            color = Morandi.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Morandi.accent.copy(alpha = 0.12f))
                .clickable { onReset() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ShortcutSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(ChipShape)
            .background(Morandi.panelHi.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = Morandi.subText.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.shortcut_search_hint),
                    color = Morandi.subText.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Morandi.text, fontSize = 12.sp),
                cursorBrush = SolidColor(Morandi.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = stringResource(R.string.common_clear),
                tint = Morandi.subText,
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onQueryChange("") }
                    .padding(3.dp),
            )
        }
    }
}

@Composable
private fun ShortcutCategoryBar(
    activeCategory: ShortcutCategory?,
    onSelect: (ShortcutCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Morandi.panelHi.copy(alpha = 0.4f))
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ShortcutChip(
            label = stringResource(R.string.shortcut_cat_all),
            selected = activeCategory == null,
            onClick = { onSelect(null) },
        )
        ShortcutCategory.entries.forEach { category ->
            ShortcutChip(
                label = stringResource(category.titleRes),
                selected = activeCategory == category,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) Morandi.accent else Morandi.subText,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.panel else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ShortcutSchemeRow(
    presetLabel: String,
    onOpenPresets: () -> Unit,
    onOpenScheme: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(ChipShape)
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .clickable { onOpenPresets() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.shortcut_preset_label),
                color = Morandi.subText,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                presetLabel,
                color = Morandi.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = Morandi.subText.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp),
            )
        }
        Row(
            modifier = Modifier
                .clip(ChipShape)
                .background(Morandi.panelHi.copy(alpha = 0.5f))
                .clickable { onOpenScheme() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = null,
                tint = Morandi.icon,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.shortcut_scheme_title),
                color = Morandi.text,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ShortcutRow(
    title: String,
    keys: List<String>,
    customized: Boolean,
    hasConflict: Boolean,
    onRecord: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onRecord() }
            .padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasConflict) {
            Icon(
                painter = painterResource(R.drawable.ic_alert_triangle),
                contentDescription = stringResource(R.string.shortcut_conflict_title),
                tint = Morandi.error,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            title,
            color = Morandi.text,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (customized) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.shortcut_reset_row),
                tint = Morandi.accent.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onReset() }
                    .padding(3.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (keys.isEmpty()) {
                ShortcutKeyCapsule(text = "—", state = CapsuleState.EMPTY)
            } else {
                keys.take(MAX_SHORTCUT_KEYS_PER_ACTION).forEach { key ->
                    ShortcutKeyCapsule(
                        text = key,
                        state = if (hasConflict) CapsuleState.CONFLICT else CapsuleState.BOUND,
                    )
                }
                if (keys.size > MAX_SHORTCUT_KEYS_PER_ACTION) {
                    ShortcutKeyCapsule(
                        text = "+${keys.size - MAX_SHORTCUT_KEYS_PER_ACTION}",
                        state = CapsuleState.EMPTY,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutKeyCapsule(
    text: String,
    state: CapsuleState,
) {
    val background = when (state) {
        CapsuleState.BOUND -> Morandi.accent.copy(alpha = 0.14f)
        CapsuleState.EMPTY -> Morandi.panelHi.copy(alpha = 0.35f)
        CapsuleState.CONFLICT -> Morandi.error.copy(alpha = 0.16f)
    }
    val content = when (state) {
        CapsuleState.BOUND -> Morandi.accent
        CapsuleState.EMPTY -> Morandi.subText.copy(alpha = 0.55f)
        CapsuleState.CONFLICT -> Morandi.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = content,
            fontSize = 10.sp,
            fontWeight = if (state == CapsuleState.BOUND) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun ShortcutGlobalOptions(
    autoRepeat: Boolean,
    onAutoRepeatChange: (Boolean) -> Unit,
    holdTemporary: Boolean,
    onHoldTemporaryChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(Morandi.panelHi.copy(alpha = 0.38f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShortcutOptionRow(
            title = stringResource(R.string.shortcut_hold_temporary),
            desc = stringResource(R.string.shortcut_hold_temporary_desc),
            checked = holdTemporary,
            onCheckedChange = onHoldTemporaryChange,
        )
        ShortcutOptionRow(
            title = stringResource(R.string.shortcut_auto_repeat),
            desc = stringResource(R.string.shortcut_auto_repeat_desc),
            checked = autoRepeat,
            onCheckedChange = onAutoRepeatChange,
        )
        Text(
            stringResource(R.string.shortcut_space_hint),
            color = Morandi.subText.copy(alpha = 0.75f),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ShortcutOptionRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Morandi.text, fontSize = 12.sp)
            Text(desc, color = Morandi.subText, fontSize = 10.sp)
        }
        ReSwitch(checked = checked, onChecked = onCheckedChange)
    }
}

// ---------------------------------------------------------------------------
// 按键录制
// ---------------------------------------------------------------------------

@Composable
private fun ShortcutRecordDialog(
    vm: PaintViewModel,
    def: ShortcutDefinition,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    // 录制期间让全局快捷键与手写笔按键全部让路
    DisposableEffect(Unit) {
        vm.isShortcutRecordingActive = true
        onDispose { vm.isShortcutRecordingActive = false }
    }
    val existing = vm.getShortcutKeys(def.id)
    var primary by remember { mutableStateOf(existing.getOrNull(0).orEmpty()) }
    var secondary by remember { mutableStateOf(existing.getOrNull(1).orEmpty()) }
    var activeSlot by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Morandi.panel)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    // 裸 Esc 关闭对话框；带修饰键的 Esc 仍然可以被录成绑定
                    if (event.key == Key.Escape &&
                        !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
                    ) {
                        onDismiss()
                        return@onKeyEvent true
                    }
                    val keyStr = keyEventToString(event)
                    if (keyStr.isBlank()) return@onKeyEvent false
                    if (activeSlot == 0) primary = keyStr else secondary = keyStr
                    true
                }
                .padding(16.dp),
        ) {
            LaunchedEffect(Unit) {
                try {
                    focusRequester.requestFocus()
                } catch (_: Exception) {
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.settings_shortcut_dialog_title),
                    color = Morandi.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(def.nameRes),
                    color = Morandi.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )

                ShortcutSlotField(
                    label = stringResource(R.string.shortcut_slot_primary),
                    value = primary,
                    active = activeSlot == 0,
                    onClick = { activeSlot = 0 },
                )
                ShortcutSlotField(
                    label = stringResource(R.string.shortcut_slot_secondary),
                    value = secondary,
                    active = activeSlot == 1,
                    onClick = { activeSlot = 1 },
                )

                if (def.holdTemporary && vm.shortcutHoldTemporaryTools) {
                    Text(
                        stringResource(R.string.shortcut_hold_temporary_hint),
                        color = Morandi.accent.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                    )
                }
                Text(
                    stringResource(R.string.shortcut_record_hint),
                    color = Morandi.subText,
                    fontSize = 10.sp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.shortcut_record_clear),
                        color = Morandi.subText,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (activeSlot == 0) primary = "" else secondary = ""
                            }
                            .padding(8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.common_cancel),
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDismiss() }
                                .padding(8.dp),
                        )
                        Text(
                            stringResource(R.string.common_save),
                            color = Morandi.onAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.accent)
                                .clickable {
                                    val keys = listOf(primary, secondary)
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .distinctBy { normalizeShortcut(it) }
                                    onSave(keys)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutSlotField(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Morandi.subText, fontSize = 10.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (active) Morandi.accent.copy(alpha = 0.12f) else Morandi.panelHi.copy(alpha = 0.45f)
                )
                .clickable { onClick() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value.ifBlank { stringResource(R.string.shortcut_slot_empty) },
                color = if (value.isBlank()) Morandi.subText else Morandi.text,
                fontSize = 13.sp,
                fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (active) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.settings_shortcut_press_key),
                    color = Morandi.accent.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 冲突确认 / 方案选择 / 导入导出
// ---------------------------------------------------------------------------

@Composable
private fun ShortcutConflictDialog(
    request: ShortcutConflictRequest,
    onCancel: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(R.string.shortcut_conflict_title),
                color = Morandi.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        R.string.shortcut_conflict_message,
                        request.keys.joinToString(", "),
                    ),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
                request.conflicts.forEach { conflicting ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_alert_triangle),
                            contentDescription = null,
                            tint = Morandi.error,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(conflicting.nameRes),
                            color = Morandi.text,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            DialogActionText(
                text = stringResource(R.string.shortcut_conflict_replace),
                color = Morandi.accent,
                bold = true,
            ) { onReplace() }
        },
        dismissButton = {
            DialogActionText(
                text = stringResource(R.string.shortcut_conflict_keep),
                color = Morandi.subText,
            ) { onCancel() }
        },
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun ShortcutPresetDialog(
    activePreset: ShortcutPreset?,
    onDismiss: () -> Unit,
    onPick: (ShortcutPreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.shortcut_preset_dialog_title),
                color = Morandi.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.shortcut_preset_hint),
                    color = Morandi.subText,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                ShortcutPreset.entries.forEach { preset ->
                    val selected = preset == activePreset
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) Morandi.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onPick(preset) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_grid),
                            contentDescription = null,
                            tint = if (selected) Morandi.accent else Morandi.subText.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(preset.labelRes),
                            color = if (selected) Morandi.accent else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogActionText(
                text = stringResource(R.string.common_close),
                color = Morandi.accent,
            ) { onDismiss() }
        },
        containerColor = Morandi.panel,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun DialogActionText(
    text: String,
    color: Color,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ShortcutSchemeDialog(
    vm: PaintViewModel,
    exported: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = 导出, 1 = 导入
    var importText by remember { mutableStateOf("") }

    fun clipboard(): android.content.ClipboardManager? =
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Morandi.panel)
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.shortcut_scheme_title),
                    color = Morandi.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.45f))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf(
                        stringResource(R.string.shortcut_scheme_export),
                        stringResource(R.string.shortcut_scheme_import),
                    ).forEachIndexed { index, label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (mode == index) Morandi.panel else Color.Transparent)
                                .clickable { mode = index }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (mode == index) Morandi.accent else Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = if (mode == index) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Text(
                    stringResource(
                        if (mode == 0) R.string.shortcut_scheme_export_hint else R.string.shortcut_scheme_import_hint
                    ),
                    color = Morandi.subText.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                )

                if (mode == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.32f))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        Text(
                            exported.ifBlank { stringResource(R.string.shortcut_none) },
                            color = Morandi.text,
                            fontSize = 10.sp,
                        )
                    }
                } else {
                    if (importText.isNotEmpty()) TextInputGuard(vm)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.32f))
                            .padding(10.dp),
                    ) {
                        if (importText.isEmpty()) {
                            Text(
                                stringResource(R.string.shortcut_scheme_import_hint),
                                color = Morandi.subText.copy(alpha = 0.55f),
                                fontSize = 10.sp,
                            )
                        }
                        BasicTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            textStyle = TextStyle(color = Morandi.text, fontSize = 10.sp),
                            cursorBrush = SolidColor(Morandi.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (mode == 0) {
                        SchemeActionButton(
                            label = stringResource(R.string.shortcut_scheme_copy),
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            clipboard()?.setPrimaryClip(
                                android.content.ClipData.newPlainText("ReveriePaint Shortcuts", exported)
                            )
                            vm.showActionToast(R.string.shortcut_scheme_copied, R.drawable.ic_copy)
                        }
                    } else {
                        SchemeActionButton(
                            label = stringResource(R.string.shortcut_scheme_paste),
                            primary = false,
                            modifier = Modifier.weight(1f),
                        ) {
                            val text = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isBlank()) {
                                vm.showActionToast(R.string.shortcut_scheme_clipboard_empty, R.drawable.ic_copy)
                            } else {
                                importText = text
                            }
                        }
                        SchemeActionButton(
                            label = stringResource(R.string.common_confirm),
                            primary = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            val count = vm.importShortcutScheme(importText)
                            if (count <= 0) {
                                vm.showActionToast(R.string.shortcut_import_invalid, R.drawable.ic_alert_triangle)
                            } else {
                                vm.showActionToast(R.string.shortcut_import_success, R.drawable.ic_check, count)
                                onDismiss()
                            }
                        }
                    }
                }

                Text(
                    stringResource(R.string.common_close),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SchemeActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Morandi.accent else Morandi.panelHi.copy(alpha = 0.45f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Morandi.onAccent else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
