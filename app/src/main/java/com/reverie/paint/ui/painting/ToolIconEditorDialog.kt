/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.deleteUnusedTempIconFile
import com.reverie.paint.core.importCustomToolIconImage
import com.reverie.paint.core.resolveCustomToolIconFile
import com.reverie.paint.core.setCustomToolIcon
import com.reverie.paint.model.CustomToolIcon
import com.reverie.paint.model.CustomToolIconCodec
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.painting.panels.BUILTIN_TOOL_ICON_CATALOG
import com.reverie.paint.ui.painting.panels.ToolIconGlyph
import com.reverie.paint.ui.painting.panels.displayName
import com.reverie.paint.ui.painting.panels.usesColorSelectEmphasis
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlinx.coroutines.launch

private val QUICK_TEXT_SYMBOLS = listOf(
    "🖌️", "✏️", "🖍️", "✒️", "🧽", "🎨",
    "💧", "🪣", "🌈", "✂️", "📐", "📏",
    "🔍", "✋", "✨", "⭐", "★", "✦",
    "◆", "●", "▲", "❖", "♡", "🌙",
)

@Composable
fun ToolIconEditorDialog(
    tool: Tool,
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val toolLabel = tool.displayName
    val existing = remember(tool.id) { vm.customToolIcons[tool.id] }
    var selectedTab by remember(tool.id) {
        mutableIntStateOf(
            when (existing) {
                is CustomToolIcon.Text -> 0
                is CustomToolIcon.Builtin -> 1
                is CustomToolIcon.Image -> 2
                null -> 0
            },
        )
    }
    var textDraft by remember(tool.id) {
        mutableStateOf(
            (existing as? CustomToolIcon.Text)?.glyph
                ?: CustomToolIconCodec.normalizeGlyph(toolLabel, maxClusters = 1),
        )
    }
    var builtinDraftKey by remember(tool.id) {
        mutableStateOf((existing as? CustomToolIcon.Builtin)?.iconKey ?: BUILTIN_TOOL_ICON_CATALOG.first().first)
    }
    var imageDraftFileName by remember(tool.id) {
        mutableStateOf((existing as? CustomToolIcon.Image)?.fileName)
    }
    var imageTintDraft by remember(tool.id) {
        mutableStateOf((existing as? CustomToolIcon.Image)?.tintWithTheme ?: false)
    }
    var pendingTempFileName by remember(tool.id) { mutableStateOf<String?>(null) }
    var importError by remember(tool.id) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = vm.importCustomToolIconImage(tool, uri, imageTintDraft)
            if (imported != null) {
                val oldPending = pendingTempFileName
                if (oldPending != null && oldPending != imported.fileName) {
                    vm.deleteUnusedTempIconFile(oldPending)
                }
                pendingTempFileName = imported.fileName
                imageDraftFileName = imported.fileName
                importError = false
                selectedTab = 2
            } else {
                importError = true
            }
        }
    }

    DisposableEffect(tool.id) {
        onDispose {
            pendingTempFileName?.let { vm.deleteUnusedTempIconFile(it) }
        }
    }

    val draftIcon: CustomToolIcon? = when (selectedTab) {
        0 -> CustomToolIconCodec.normalizeGlyph(textDraft).takeIf { it.isNotEmpty() }?.let { CustomToolIcon.Text(it) }
        1 -> CustomToolIcon.Builtin(builtinDraftKey)
        else -> imageDraftFileName?.let { CustomToolIcon.Image(it, tintWithTheme = imageTintDraft) }
    }
    val canApply = when (selectedTab) {
        0 -> CustomToolIconCodec.normalizeGlyph(textDraft).isNotEmpty()
        1 -> true
        else -> imageDraftFileName != null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 420.dp)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Morandi.panel)
                .glassBorder(RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header + Live Normal / Selected Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tool_icon_editor_title),
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = toolLabel,
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PreviewSlot(
                        tool = tool,
                        icon = draftIcon,
                        selected = false,
                        vm = vm,
                    )
                    PreviewSlot(
                        tool = tool,
                        icon = draftIcon,
                        selected = true,
                        vm = vm,
                    )
                }

                Spacer(Modifier.height(14.dp))

                // 3-Tab Switcher
                val tabLabels = listOf(
                    stringResource(R.string.tool_icon_tab_text),
                    stringResource(R.string.tool_icon_tab_builtin),
                    stringResource(R.string.tool_icon_tab_image),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tabLabels.forEachIndexed { idx, title ->
                        val active = selectedTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Morandi.accent else Color.Transparent)
                                .clickable { selectedTab = idx }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title,
                                color = if (active) Morandi.onAccent else Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Tab Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(236.dp),
                ) {
                    when (selectedTab) {
                        0 -> TextEmojiTabContent(
                            textDraft = textDraft,
                            onTextChange = { raw ->
                                textDraft = CustomToolIconCodec.normalizeGlyph(raw)
                            },
                        )
                        1 -> BuiltinIconTabContent(
                            selectedKey = builtinDraftKey,
                            onSelectKey = { builtinDraftKey = it },
                        )
                        else -> ImageIconTabContent(
                            hasImage = imageDraftFileName != null,
                            tintWithTheme = imageTintDraft,
                            importError = importError,
                            onPickImage = { pickImageLauncher.launch("image/*") },
                            onToggleTint = { imageTintDraft = it },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Bottom Actions: Restore Default / Cancel / Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.tool_icon_restore_default),
                        onClick = {
                            vm.setCustomToolIcon(tool, null)
                            onDismiss()
                        },
                        textColor = Morandi.subText,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            textColor = Morandi.subText,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (canApply) Morandi.accent else Morandi.panelHi,
                                )
                                .clickable(enabled = canApply) {
                                    draftIcon?.let { iconToSave ->
                                        vm.setCustomToolIcon(tool, iconToSave)
                                    }
                                    onDismiss()
                                }
                                .padding(horizontal = 18.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.tool_icon_apply),
                                color = if (canApply) Morandi.onAccent else Morandi.subText.copy(alpha = 0.5f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSlot(
    tool: Tool,
    icon: CustomToolIcon?,
    selected: Boolean,
    vm: PaintViewModel,
) {
    val contentDesc = tool.displayName
    val useColorEmphasis = icon.usesColorSelectEmphasis()
    val tint = if (selected) Morandi.accent else Morandi.icon
    val previewScale = if (useColorEmphasis) (if (selected) 1.12f else 0.94f) else 1f
    val previewAlpha = if (useColorEmphasis && !selected) 0.62f else 1f
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .border(
                width = 0.5.dp,
                color = if (selected && !useColorEmphasis) Morandi.accent.copy(alpha = 0.5f) else Morandi.subText.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = previewScale
                scaleY = previewScale
                alpha = previewAlpha
            },
        ) {
            ToolIconGlyph(
                tool = tool,
                customIcon = icon,
                tint = tint,
                size = 20.dp,
                contentDescription = contentDesc,
                resolveFile = { vm.resolveCustomToolIconFile(it) },
            )
        }
    }
}

@Composable
private fun TextEmojiTabContent(
    textDraft: String,
    onTextChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tool_icon_text_hint),
            color = Morandi.subText,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .glassBorder(RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = textDraft,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Morandi.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                ),
                cursorBrush = SolidColor(Morandi.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.tool_icon_quick_symbols),
            color = Morandi.subText,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(QUICK_TEXT_SYMBOLS) { symbol ->
                val isSelected = textDraft == symbol
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Morandi.accent.copy(alpha = 0.2f) else Morandi.panelHi)
                        .then(
                            if (isSelected) Modifier.border(1.dp, Morandi.accent, RoundedCornerShape(8.dp))
                            else Modifier,
                        )
                        .clickable { onTextChange(symbol) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = symbol,
                        color = Morandi.text,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltinIconTabContent(
    selectedKey: String,
    onSelectKey: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(42.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(BUILTIN_TOOL_ICON_CATALOG, key = { it.first }) { (iconKey, resId) ->
            val isSelected = selectedKey == iconKey
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Morandi.accent.copy(alpha = 0.22f) else Morandi.panelHi)
                    .then(
                        if (isSelected) Modifier.border(1.dp, Morandi.accent, RoundedCornerShape(9.dp))
                        else Modifier,
                    )
                    .clickable { onSelectKey(iconKey) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(resId),
                    contentDescription = iconKey,
                    tint = if (isSelected) Morandi.accent else Morandi.icon,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ImageIconTabContent(
    hasImage: Boolean,
    tintWithTheme: Boolean,
    importError: Boolean,
    onPickImage: () -> Unit,
    onToggleTint: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.accent.copy(alpha = 0.16f))
                .border(1.dp, Morandi.accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .clickable(onClick = onPickImage)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(
                        if (hasImage) R.string.tool_icon_replace_image else R.string.tool_icon_pick_image,
                    ),
                    color = Morandi.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            text = stringResource(R.string.tool_icon_image_hint),
            color = Morandi.subText,
            fontSize = 11.5.sp,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Morandi.panelHi)
                .clickable { onToggleTint(!tintWithTheme) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tool_icon_image_tint),
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.tool_icon_image_tint_sub),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            ReSwitch(
                checked = tintWithTheme,
                onChecked = onToggleTint,
            )
        }

        if (importError) {
            Text(
                text = stringResource(R.string.tool_icon_image_failed),
                color = Color(0xFFE06C75),
                fontSize = 12.sp,
            )
        }
    }
}
