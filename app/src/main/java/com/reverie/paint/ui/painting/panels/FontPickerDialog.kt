/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.FontItem
import com.reverie.paint.core.FontManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Morandi

/**
 * 字体选择器弹窗 (支持系统预设分类与外部 TTF/OTF 自定义字体导入、预览与管理)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontPickerDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cfg = vm.typographyConfig
    var customFonts by remember { mutableStateOf(FontManager.loadCustomFonts(context)) }
    val presetFonts = remember { FontManager.getPresets(context) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val item = FontManager.importFontFromUri(context, uri)
            if (item != null) {
                customFonts = FontManager.loadCustomFonts(context)
                vm.typographyConfig = vm.typographyConfig.copy(
                    fontFamilyName = item.id,
                    fontPath = item.filePath,
                )
                vm.showActionToast(R.string.typography_font_import_success, R.drawable.ic_check)
            } else {
                vm.showActionToast(R.string.typography_font_import_failed, R.drawable.ic_alert_triangle)
            }
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(16.dp))
            .background(Morandi.panelHi)
            .border(1.dp, Morandi.border, RoundedCornerShape(16.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶栏：标题与关闭按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_text),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.typography_font_picker_title),
                        color = Morandi.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Morandi.panel)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = null,
                        tint = Morandi.subText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 字体列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                // 1. 系统与预设字体分类
                item {
                    Text(
                        text = stringResource(R.string.typography_font_preset_category),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                    )
                }

                items(presetFonts, key = { it.id }) { item ->
                    val isSelected = cfg.fontPath.isNullOrBlank() && (
                        cfg.fontFamilyName == item.id ||
                            (item.id == "system:default" && (cfg.fontFamilyName == "default" || cfg.fontFamilyName == "系统默认")) ||
                            (item.id == "system:serif" && (cfg.fontFamilyName == "serif" || cfg.fontFamilyName == "衬线体")) ||
                            (item.id == "system:monospace" && (cfg.fontFamilyName == "monospace" || cfg.fontFamilyName == "等宽体")) ||
                            (item.id == "system:cursive" && (cfg.fontFamilyName == "cursive" || cfg.fontFamilyName == "手写体"))
                    )

                    FontItemCard(
                        font = item,
                        selected = isSelected,
                        onSelect = {
                            vm.typographyConfig = cfg.copy(fontFamilyName = item.id, fontPath = null)
                        },
                    )
                }

                // 2. 自定义导入字体分类
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.typography_font_custom_category),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                    )
                }

                if (customFonts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panel.copy(alpha = 0.5f))
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.typography_font_empty_custom),
                                color = Morandi.subText,
                                fontSize = 12.sp,
                            )
                        }
                    }
                } else {
                    items(customFonts, key = { it.id }) { item ->
                        val isSelected = cfg.fontPath == item.filePath || cfg.fontFamilyName == item.id

                        FontItemCard(
                            font = item,
                            selected = isSelected,
                            onSelect = {
                                vm.typographyConfig = cfg.copy(
                                    fontFamilyName = item.id,
                                    fontPath = item.filePath,
                                )
                            },
                            onDelete = {
                                FontManager.deleteCustomFont(context, item)
                                customFonts = FontManager.loadCustomFonts(context)
                                if (isSelected) {
                                    vm.typographyConfig = cfg.copy(fontFamilyName = "system:default", fontPath = null)
                                }
                            },
                        )
                    }
                }
            }

            // 底部操作按钮：导入本地字体
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.accent.copy(alpha = 0.12f))
                    .border(1.dp, Morandi.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable { importLauncher.launch(arrayOf("*/*")) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_plus),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.typography_font_import_btn),
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun FontItemCard(
    font: FontItem,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val sampleTypeface = remember(font) { FontManager.getTypefaceForItem(context, font) }
    val sampleFontFamily = remember(sampleTypeface) { FontFamily(sampleTypeface) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.14f) else Morandi.panel)
            .border(
                1.dp,
                if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 单选状态圆环
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (selected) Morandi.accent else Color.Transparent)
                    .border(1.5.dp, if (selected) Morandi.accent else Morandi.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }

            // 字体名与真实字体预览
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = font.displayName,
                    color = if (selected) Morandi.accent else Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = stringResource(R.string.typography_font_preview_sample),
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    fontFamily = sampleFontFamily,
                    maxLines = 1,
                )
            }
        }

        // 删除按钮 (仅限自定义字体)
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.typography_font_delete),
                    tint = Morandi.subText.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
