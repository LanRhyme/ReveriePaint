/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi

/**
 * Tab 2: Palettes Page with:
 * - Full palette management (create, duplicate, rename, delete)
 * - Intelligent palette extraction from photo / camera
 * - Compact square swatches grid with active color indicator
 * - Complete Morandi theme styling
 */
@Composable
fun PalettesPage(
    vm: PaintViewModel,
    onColorSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showCreatePaletteDialog by remember { mutableStateOf(false) }
    var newPaletteName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf<PaintViewModel.ColorPaletteItem?>(null) }
    var renamePaletteText by remember { mutableStateOf("") }
    var showAddColorPalettePicker by remember { mutableStateOf(false) }
    var showTopPlusMenu by remember { mutableStateOf(false) }
    var activeMenuPalette by remember { mutableStateOf<PaintViewModel.ColorPaletteItem?>(null) }

    // Image Picker Launcher for palette extraction
    val importPaletteImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(input)
                input?.close()
                if (bitmap != null) {
                    vm.importPaletteFromBitmap(bitmap, "图片色卡")
                    Toast.makeText(context, "已智能提取 30 色莫兰迪感知色卡", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Camera Capture Launcher for instant real-world palette extraction
    val takeCameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            try {
                vm.importPaletteFromBitmap(bitmap, "拍摄色卡")
                Toast.makeText(context, "已智能提取 30 色实景感知色卡", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "拍摄色卡提取失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top Action Bar on Palettes Page: [💧+ Add Color to Palette] and [+ Create / Import]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [💧+] Button: Add color to chosen palette
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { showAddColorPalettePicker = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = "添加颜色至色卡",
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // [+] Button: Create / Import Palette
            Box {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { showTopPlusMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Morandi.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Light
                    )
                }

                DropdownMenu(
                    expanded = showTopPlusMenu,
                    onDismissRequest = { showTopPlusMenu = false },
                    modifier = Modifier
                        .background(Morandi.panel)
                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("新建色卡", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder_plus),
                                contentDescription = null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            showTopPlusMenu = false
                            newPaletteName = ""
                            showCreatePaletteDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从图片提取色卡", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_bookmark_plus),
                                contentDescription = null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            showTopPlusMenu = false
                            importPaletteImageLauncher.launch("image/*")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("从相机拍摄色卡", color = Morandi.text, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_image_adjust),
                                contentDescription = null,
                                tint = Morandi.icon,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        onClick = {
                            showTopPlusMenu = false
                            takeCameraPreviewLauncher.launch(null)
                        }
                    )
                }
            }
        }

        // Scrollable list of palettes
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (vm.allPalettes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无色卡，点击右上角 + 创建", color = Morandi.subText, fontSize = 12.sp)
                }
            }

            for (palette in vm.allPalettes) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header: Palette Name + [⋯] menu
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = palette.name,
                            color = Morandi.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Box {
                            Text(
                                text = "⋯",
                                color = Morandi.subText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { activeMenuPalette = palette }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )

                            DropdownMenu(
                                expanded = activeMenuPalette?.id == palette.id,
                                onDismissRequest = { activeMenuPalette = null },
                                modifier = Modifier
                                    .background(Morandi.panel)
                                    .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制色卡", color = Morandi.text, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_copy),
                                            contentDescription = null,
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        vm.duplicatePalette(palette.id)
                                        activeMenuPalette = null
                                        Toast.makeText(context, "已复制色卡", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名", color = Morandi.text, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_brush),
                                            contentDescription = null,
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        renamePaletteText = palette.name
                                        showRenameDialog = palette
                                        activeMenuPalette = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除色卡", color = Morandi.accentHi, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_erase),
                                            contentDescription = null,
                                            tint = Morandi.accentHi,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                    onClick = {
                                        vm.deletePalette(palette.id)
                                        activeMenuPalette = null
                                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    // Dynamic row count Grid with Compact Square Swatches
                    SquarePaletteSwatchesGrid(
                        colors = palette.colors,
                        selectedColor = vm.brushColor,
                        onColorSelect = onColorSelected,
                        onColorLongPress = { colorIdx ->
                            vm.removeColorFromPalette(palette.id, colorIdx)
                            Toast.makeText(context, "已移除颜色", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Dialog: Add Current Color to Palette Picker
    if (showAddColorPalettePicker) {
        AlertDialog(
            onDismissRequest = { showAddColorPalettePicker = false },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("添加当前颜色至色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (pal in vm.allPalettes) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panelHi)
                                .border(1.dp, Morandi.border.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .clickable {
                                vm.addColorToPalette(pal.id, vm.brushColor)
                                showAddColorPalettePicker = false
                                Toast.makeText(context, "已存入 ${pal.name}", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = pal.name, color = Morandi.text, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                ReTextButton("取消", { showAddColorPalettePicker = false }, textColor = Morandi.subText)
            }
        )
    }

    // Dialog: Create New Palette
    if (showCreatePaletteDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePaletteDialog = false },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("新建色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPaletteName,
                    onValueChange = { newPaletteName = it },
                    placeholder = { Text("请输入色卡名称", color = Morandi.subText, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                ReTextButton(
                    "创建",
                    onClick = {
                        if (newPaletteName.isNotBlank()) {
                            vm.createNewPalette(newPaletteName.trim(), listOf(vm.brushColor))
                            showCreatePaletteDialog = false
                        }
                    },
                    textColor = Morandi.accent,
                )
            },
            dismissButton = {
                ReTextButton("取消", { showCreatePaletteDialog = false }, textColor = Morandi.subText)
            }
        )
    }

    // Dialog: Rename Palette
    if (showRenameDialog != null) {
        val palToRename = showRenameDialog!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            containerColor = Morandi.panel,
            shape = RoundedCornerShape(14.dp),
            title = { Text("重命名色卡", color = Morandi.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renamePaletteText,
                    onValueChange = { renamePaletteText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                ReTextButton(
                    "确定",
                    onClick = {
                        if (renamePaletteText.isNotBlank()) {
                            vm.renamePalette(palToRename.id, renamePaletteText.trim())
                            showRenameDialog = null
                        }
                    },
                    textColor = Morandi.accent,
                )
            },
            dismissButton = {
                ReTextButton("取消", { showRenameDialog = null }, textColor = Morandi.subText)
            }
        )
    }
}
