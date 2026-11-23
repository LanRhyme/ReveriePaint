/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.model.*
import org.json.JSONArray
import org.json.JSONObject
import com.reverie.paint.core.PaintViewModel.ColorPaletteItem

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.addColorToPalette(paletteId: String, hex: String) {
    val upper = hex.uppercase()
    userPalettes = userPalettes.map {
        if (it.id == paletteId) {
            val list = it.colors.toMutableList()
            list.add(upper)
            it.copy(colors = list)
        } else it
    }
    persistUserPalettes()
}

internal fun PaintViewModel.removeColorFromPalette(paletteId: String, index: Int) {
    userPalettes = userPalettes.map {
        if (it.id == paletteId && index in it.colors.indices) {
            val list = it.colors.toMutableList()
            list.removeAt(index)
            it.copy(colors = list)
        } else it
    }
    persistUserPalettes()
}

internal fun PaintViewModel.createNewPalette(name: String, initialColors: List<String> = emptyList()) {
    val newPal = ColorPaletteItem(
        id = "palette_${System.currentTimeMillis()}",
        name = name.ifBlank { "新建色卡" },
        colors = initialColors
    )
    userPalettes = userPalettes + newPal
    persistUserPalettes()
}

internal fun PaintViewModel.duplicatePalette(paletteId: String) {
    val src = allPalettes.firstOrNull { it.id == paletteId } ?: return
    val newPal = ColorPaletteItem(
        id = "palette_${System.currentTimeMillis()}",
        name = "${src.name} (副本)",
        colors = src.colors.toList()
    )
    userPalettes = userPalettes + newPal
    persistUserPalettes()
}

internal fun PaintViewModel.renamePalette(paletteId: String, newName: String) {
    if (newName.isBlank()) return
    userPalettes = userPalettes.map {
        if (it.id == paletteId) it.copy(name = newName) else it
    }
    persistUserPalettes()
}

internal fun PaintViewModel.deletePalette(paletteId: String) {
    userPalettes = userPalettes.filterNot { it.id == paletteId }
    persistUserPalettes()
}

internal fun PaintViewModel.importPaletteFromBitmap(bmp: android.graphics.Bitmap, name: String) {
    val scaled = if (bmp.width > 240 || bmp.height > 240) {
        val ratio = minOf(240f / bmp.width, 240f / bmp.height)
        android.graphics.Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    } else bmp
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)

    val extracted = ColorQuantizer.extractPalette(pixels, targetCount = 30)
    createNewPalette(name.ifBlank { "智能提取色卡" }, extracted)
}

internal fun PaintViewModel.addRecentColor(hex: String) {
    val upper = hex.uppercase()
    val list = recentColors.filterNot { it.equals(upper, ignoreCase = true) }.toMutableList()
    list.add(0, upper)
    recentColors = list.take(16)
    persistRecentColors()
}

internal fun PaintViewModel.clearRecentColors() {
    recentColors = emptyList()
    persistRecentColors()
}

internal fun PaintViewModel.persistRecentColors() {
    if (isAppContextReady()) {
        appContext
            .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("recentColors", recentColors.joinToString(","))
            .apply()
    }
}

internal fun PaintViewModel.loadRecentColors(): List<String> {
    if (isAppContextReady()) {
        val saved = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .getString("recentColors", null)
        if (!saved.isNullOrBlank()) {
            return saved.split(",").filter { it.isNotBlank() }.take(16)
        }
    }
    return listOf(
        "#FFFFFF", "#D6D6D6", "#ADADAD", "#858585", "#5C5C5C", "#333333", "#141414", "#000000",
        "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#00BCD4", "#2196F3", "#9C27B0", "#E91E63"
    )
}

internal fun PaintViewModel.persistUserPalettes() {
    if (isAppContextReady()) {
        try {
            val arr = org.json.JSONArray()
            for (p in userPalettes) {
                val o = org.json.JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                val colorsArr = org.json.JSONArray()
                p.colors.forEach { colorsArr.put(it) }
                o.put("colors", colorsArr)
                arr.put(o)
            }
            appContext
                .getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("userPalettes", arr.toString())
                .apply()
        } catch (e: Exception) { }
    }
}

internal fun PaintViewModel.loadUserPalettes(): List<ColorPaletteItem> {
    if (isAppContextReady()) {
        val json = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .getString("userPalettes", null)
        if (!json.isNullOrBlank()) {
            return runCatching {
                val arr = org.json.JSONArray(json)
                val list = mutableListOf<ColorPaletteItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getString("id")
                    val name = o.getString("name")
                    val colorsArr = o.getJSONArray("colors")
                    val colors = mutableListOf<String>()
                    for (j in 0 until colorsArr.length()) {
                        colors.add(colorsArr.getString(j))
                    }
                    list.add(ColorPaletteItem(id, name, colors))
                }
                list
            }.getOrDefault(emptyList())
        }
    }
    return listOf(
        ColorPaletteItem(
            "builtin_basic", "基本色",
            listOf(
                "#000000", "#FFFFFF", "#FEEBD0", "#FFF000", "#FFA500", "#FF4500", "#E60000", "#990000",
                "#99CC00", "#339900", "#009944", "#00A0E9", "#0068B7", "#1D2088", "#601986", "#4A225D"
            )
        ),
        ColorPaletteItem(
            "builtin_morandi", "莫兰迪",
            listOf(
                "#B8A18F", "#9C8578", "#827065", "#A0A59A", "#8B958D", "#737F79", "#A1A0B0", "#838294",
                "#978D7E", "#847A6B", "#6F6659", "#B49F82", "#A18A6C", "#8B7457", "#9C7C7C", "#806363"
            )
        )
    )
}

internal fun PaintViewModel.reloadPalettesFromPrefs() {
    userPalettes = loadUserPalettes()
    recentColors = loadRecentColors()
}
