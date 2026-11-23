/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Build
import com.reverie.paint.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.updateUiOpacity(v: Float) {
    uiOpacity = v
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("uiOpacity", v)
            .apply()
    }
}

internal fun PaintViewModel.updatePopupPanelOpacity(v: Float) {
    popupPanelOpacity = v
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("popupPanelOpacity", v)
            .apply()
    }
}

internal fun PaintViewModel.updatePaintingUiScale(scale: Float) {
    paintingUiScale = scale.coerceIn(0.70f, 1.40f)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("paintingUiScale", paintingUiScale)
            .apply()
    }
}

internal fun PaintViewModel.updateLayerRowHeight(height: Int) {
    layerRowHeightDp = height.coerceIn(40, 80)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("layerRowHeightDp", layerRowHeightDp)
            .apply()
    }
}

internal fun PaintViewModel.updateQuickSliderHeight(height: Int) {
    quickSliderHeightDp = height.coerceIn(100, 260)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("quickSliderHeightDp", quickSliderHeightDp)
            .apply()
    }
}

internal fun PaintViewModel.updateLeftHandMode(enabled: Boolean) {
    leftHandMode = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("leftHandMode", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateFloatingUiEnabled(enabled: Boolean) {
    floatingUiConfig = floatingUiConfig.copy(enabled = enabled)
    persistFloatingUiConfig()
}

internal fun PaintViewModel.updateFloatingUiPositions(
    topBarPosition: com.reverie.paint.model.PanelPosition = floatingUiConfig.topBarPosition,
    toolRailPosition: com.reverie.paint.model.PanelPosition = floatingUiConfig.toolRailPosition,
    sliderRailPosition: com.reverie.paint.model.PanelPosition = floatingUiConfig.sliderRailPosition,
    panelOrder: List<String> = floatingUiConfig.panelOrder,
) {
    floatingUiConfig = floatingUiConfig.copy(
        topBarPosition = topBarPosition,
        toolRailPosition = toolRailPosition,
        sliderRailPosition = sliderRailPosition,
        panelOrder = panelOrder,
    )
    persistFloatingUiConfig()
}

internal fun PaintViewModel.updateFloatingUiMargin(marginDp: Int) {
    floatingUiConfig = floatingUiConfig.copy(marginDp = marginDp.coerceIn(8, 48))
    persistFloatingUiConfig()
}

internal fun PaintViewModel.updateFloatingUiScale(scale: Float) {
    floatingUiConfig = floatingUiConfig.copy(scale = scale.coerceIn(0.70f, 1.40f))
    persistFloatingUiConfig()
}

internal fun PaintViewModel.updateFloatingUiTopBarActions(actions: List<com.reverie.paint.model.QuickAction>) {
    floatingUiConfig = floatingUiConfig.copy(topBarActions = actions)
    persistFloatingUiConfig()
}

internal fun PaintViewModel.updateFloatingUiPanelOrder(order: List<String>) {
    floatingUiConfig = floatingUiConfig.copy(panelOrder = order)
    persistFloatingUiConfig()
}

internal fun PaintViewModel.resetFloatingUiEdges() {
    floatingUiConfig = floatingUiConfig.copy(
        topBarPosition = com.reverie.paint.model.PanelPosition(com.reverie.paint.model.PanelEdge.TOP, com.reverie.paint.model.PanelAlignment.END),
        toolRailPosition = com.reverie.paint.model.PanelPosition(com.reverie.paint.model.PanelEdge.LEFT, com.reverie.paint.model.PanelAlignment.START),
        sliderRailPosition = com.reverie.paint.model.PanelPosition(com.reverie.paint.model.PanelEdge.LEFT, com.reverie.paint.model.PanelAlignment.END),
        marginDp = 12,
        scale = 1.0f,
        topBarActions = emptyList(),
        panelOrder = com.reverie.paint.model.FloatingUiConfig.DEFAULT_PANEL_ORDER,
    )
    persistFloatingUiConfig()
}

internal fun PaintViewModel.persistFloatingUiConfig() {
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("floatingUi_enabled", floatingUiConfig.enabled)
            .putString("floatingUi_topBarEdge", floatingUiConfig.topBarPosition.edge.name)
            .putString("floatingUi_topBarAlign", floatingUiConfig.topBarPosition.alignment.name)
            .putString("floatingUi_toolRailEdge", floatingUiConfig.toolRailPosition.edge.name)
            .putString("floatingUi_toolRailAlign", floatingUiConfig.toolRailPosition.alignment.name)
            .putString("floatingUi_sliderRailEdge", floatingUiConfig.sliderRailPosition.edge.name)
            .putString("floatingUi_sliderRailAlign", floatingUiConfig.sliderRailPosition.alignment.name)
            .putInt("floatingUi_marginDp", floatingUiConfig.marginDp)
            .putFloat("floatingUi_scale", floatingUiConfig.scale)
            .putString("floatingUi_topBarActions", floatingUiConfig.topBarActions.joinToString(",") { it.id })
            .putString("floatingUi_panelOrder", floatingUiConfig.panelOrder.joinToString(","))
            .apply()
    }
}

internal fun PaintViewModel.updateLayerHeaderInheritAlpha(enabled: Boolean) {
    layerHeaderInheritAlpha = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("layerHeaderInheritAlpha", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateSelectionMaskColor(hex: String) {
    selectionMaskColorHex = hex
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("selection_mask_color", hex)
            .apply()
    }
}

internal fun PaintViewModel.updateSelectionMaskOpacity(opacity: Float) {
    selectionMaskOpacity = opacity.coerceIn(0.10f, 0.90f)
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("selection_mask_opacity", selectionMaskOpacity)
            .apply()
    }
}

internal fun PaintViewModel.updateCanvasBgColor(hex: String) {
    canvasBgColorHex = hex
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("canvasBgColor", hex)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.updateCanvasGridEnabled(enabled: Boolean) {
    canvasGridEnabled = enabled
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("canvas_grid_enabled", enabled)
            .apply()
    }
}

internal fun PaintViewModel.updateCanvasGridOpacity(opacity: Float) {
    val clamped = opacity.coerceIn(0.05f, 0.60f)
    canvasGridOpacity = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("canvas_grid_opacity", clamped)
            .apply()
    }
}

internal fun PaintViewModel.updateCanvasGridSizeDp(sizeDp: Int) {
    val clamped = sizeDp.coerceIn(12, 48)
    canvasGridSizeDp = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putInt("canvas_grid_size_dp", clamped)
            .apply()
    }
}

internal fun PaintViewModel.updateAccentColor(hex: String) {
    accentColorHex = hex
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("accentColor", hex)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.updateMonetEnabled(enable: Boolean) {
    monetEnabled = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("monetEnabled", enable)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.updateThemeMode(mode: String) {
    themeMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("themeMode", mode)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.updateThemeContrast(contrast: String) {
    themeContrast = contrast
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("themeContrast", contrast)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.updateThemeStyle(style: String) {
    themeStyle = style
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("themeStyle", style)
            .apply()
    }
    applyCurrentTheme()
}

internal fun PaintViewModel.isCurrentlyDark(): Boolean {
    return when (themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> {
            if (isAppContextReady()) {
                val uiMode =
                    appContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            } else {
                true
            }
        }
        else -> true
    }
}

internal fun PaintViewModel.applyCurrentTheme() {
    if (!isAppContextReady()) return
    val dark = isCurrentlyDark()
    val parsedAccent = com.reverie.paint.ui.theme.parseColor(accentColorHex)
    val baseTheme = if (monetEnabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        com.reverie.paint.ui.theme.getMonetColors(appContext, isDark = dark, fallbackAccent = parsedAccent, contrast = themeContrast, style = themeStyle)
    } else {
        com.reverie.paint.ui.theme.buildThemeColors(isDark = dark, accent = parsedAccent, contrast = themeContrast, style = themeStyle)
    }

    com.reverie.paint.ui.theme.Theme.current = if (canvasBgColorHex.isNotBlank() && canvasBgColorHex != "DEFAULT") {
        try {
            baseTheme.copy(canvasBg = com.reverie.paint.ui.theme.parseColor(canvasBgColorHex))
        } catch (_: Exception) {
            baseTheme
        }
    } else {
        baseTheme
    }
    com.reverie.paint.MainActivity.applySystemBarsTheme(com.reverie.paint.ui.theme.Theme.current, dark)
}

internal fun PaintViewModel.updateImmersiveMode(enable: Boolean) {
    immersiveMode = true
    val shouldImmerse = (currentPage == Page.PAINTING)
    com.reverie.paint.MainActivity
        .applyImmersive(shouldImmerse, extendToCutout)
}

internal fun PaintViewModel.updateExtendToCutout(extend: Boolean) {
    extendToCutout = extend
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("extendToCutout", extend)
            .apply()
    }
    val shouldImmerse = (currentPage == Page.PAINTING)
    com.reverie.paint.MainActivity
        .applyImmersive(shouldImmerse, extend)
}

internal fun PaintViewModel.updateBlurBackground(enable: Boolean) {
    blurBackground = enable
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("blurBackground", enable)
            .apply()
    }
}

internal fun PaintViewModel.updateCanvasOpenWaveEnabled(enable: Boolean) {
    val supported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val actual = enable && supported
    canvasOpenWaveEnabled = actual
    if (isAppContextReady()) {
        prefs
            .edit()
            .putBoolean("canvasOpenWaveEnabled", actual)
            .apply()
    }
}

internal fun PaintViewModel.getHomeCustomBgFile(): File? {
    if (!isAppContextReady()) return null
    val file = File(appContext.filesDir, "custom_bg/home_wallpaper.png")
    return if (file.exists() && file.length() > 0) file else null
}

internal suspend fun PaintViewModel.importHomeBackground(uri: android.net.Uri, context: android.content.Context): Boolean {
    val dir = File(context.filesDir, "custom_bg")
    val targetFile = File(dir, "home_wallpaper.png")
    val saved = withContext(Dispatchers.IO) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmpDownload = File(dir, "home_wallpaper_src.tmp")
            val tmpPng = File(dir, "home_wallpaper.png.tmp")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                tmpDownload.outputStream().use { out -> input.copyTo(out) }
                true
            } ?: false
            if (!copied || !tmpDownload.exists() || tmpDownload.length() <= 0L) {
                tmpDownload.delete()
                return@withContext false
            }
            val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(tmpDownload.absolutePath, boundsOpts)
            val maxEdge = 2560
            var sampleSize = 1
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW > maxEdge || srcH > maxEdge) {
                while ((srcW / (sampleSize * 2)) >= maxEdge || (srcH / (sampleSize * 2)) >= maxEdge) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decoded = android.graphics.BitmapFactory.decodeFile(tmpDownload.absolutePath, decodeOpts)
            tmpDownload.delete()
            if (decoded == null) return@withContext false
            val finalBmp = try {
                val maxDim = maxOf(decoded.width, decoded.height)
                if (maxDim > maxEdge && maxDim > 0) {
                    val scale = maxEdge.toFloat() / maxDim.toFloat()
                    val sw = (decoded.width * scale).roundToInt().coerceAtLeast(1)
                    val sh = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, sw, sh, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }
            } catch (t: Throwable) {
                decoded.recycle()
                throw t
            }
            val ok = try {
                java.io.FileOutputStream(tmpPng).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } finally {
                finalBmp.recycle()
            }
            if (!ok || !tmpPng.exists() || tmpPng.length() <= 0L) {
                tmpPng.delete()
                return@withContext false
            }
            if (targetFile.exists()) targetFile.delete()
            tmpPng.renameTo(targetFile) || (tmpPng.copyTo(targetFile, overwrite = true).let {
                tmpPng.delete()
                true
            })
        } catch (e: Exception) {
            android.util.Log.e("PaintViewModel", "Failed to import home background", e)
            false
        }
    }
    return if (saved && targetFile.exists() && targetFile.length() > 0L) {
        homeCustomBgEnabled = true
        homeCustomBgVersion = System.currentTimeMillis()
        context.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("home_custom_bg_enabled", true)
            .apply()
        true
    } else {
        false
    }
}

internal fun PaintViewModel.clearHomeBackground() {
    if (isAppContextReady()) {
        val file = File(appContext.filesDir, "custom_bg/home_wallpaper.png")
        if (file.exists()) file.delete()
        prefs
            .edit()
            .putBoolean("home_custom_bg_enabled", false)
            .apply()
    }
    homeCustomBgEnabled = false
    homeCustomBgVersion = System.currentTimeMillis()
}

internal fun PaintViewModel.updateHomeBgOpacity(opacity: Float) {
    val clamped = opacity.coerceIn(0.1f, 1.0f)
    homeCustomBgOpacity = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("home_custom_bg_opacity", clamped)
            .apply()
    }
}

internal fun PaintViewModel.updateHomeBgBlurDp(blurDp: Float) {
    val clamped = blurDp.coerceIn(0f, 30f)
    homeCustomBgBlurDp = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("home_custom_bg_blur", clamped)
            .apply()
    }
}

internal fun PaintViewModel.updateHomeBgScaleMode(mode: String) {
    homeCustomBgScaleMode = mode
    if (isAppContextReady()) {
        prefs
            .edit()
            .putString("home_custom_bg_scale", mode)
            .apply()
    }
}

internal fun PaintViewModel.updateHomeBgMaskAlpha(alpha: Float) {
    val clamped = alpha.coerceIn(0f, 0.8f)
    homeCustomBgMaskAlpha = clamped
    if (isAppContextReady()) {
        prefs
            .edit()
            .putFloat("home_custom_bg_mask", clamped)
            .apply()
    }
}
