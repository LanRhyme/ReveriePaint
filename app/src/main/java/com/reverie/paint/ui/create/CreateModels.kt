/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.create

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.reverie.paint.R
import java.util.Locale
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

enum class CanvasUnit(val symbol: String, val labelRes: Int) {
    PX("PX", R.string.create_unit_px),
    MM("mm", R.string.create_unit_mm),
    CM("cm", R.string.create_unit_cm),
    INCH("in", R.string.create_unit_inch)
}

fun pxToUnit(px: Double, unit: CanvasUnit, dpi: Int): Double {
    val safeDpi = dpi.coerceIn(72, 1200)
    return when (unit) {
        CanvasUnit.PX -> px
        CanvasUnit.INCH -> px / safeDpi
        CanvasUnit.MM -> (px / safeDpi) * 25.4
        CanvasUnit.CM -> (px / safeDpi) * 2.54
    }
}

fun unitToPx(value: Double, unit: CanvasUnit, dpi: Int): Int {
    val safeDpi = dpi.coerceIn(72, 1200)
    val px = when (unit) {
        CanvasUnit.PX -> value
        CanvasUnit.INCH -> value * safeDpi
        CanvasUnit.MM -> (value / 25.4) * safeDpi
        CanvasUnit.CM -> (value / 2.54) * safeDpi
    }
    return kotlin.math.round(px).toInt()
}

fun formatUnitValue(value: Double, unit: CanvasUnit): String {
    return when (unit) {
        CanvasUnit.PX -> kotlin.math.round(value).toInt().toString()
        CanvasUnit.MM -> {
            val rounded = kotlin.math.round(value * 10) / 10.0
            if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.US, "%.1f", rounded)
        }
        CanvasUnit.CM -> {
            val rounded = kotlin.math.round(value * 100) / 100.0
            if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.US, "%.2f", rounded)
        }
        CanvasUnit.INCH -> {
            val rounded = kotlin.math.round(value * 100) / 100.0
            if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.US, "%.2f", rounded)
        }
    }
}

data class CanvasPresetItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val width: Int,
    val height: Int,
    val defaultPpi: Int = 300,
    val description: String = "",
    val isCustom: Boolean = false
)

/**
 * Calculates realistic maximum layer capacity based on device physical available RAM & JVM Heap limit.
 * Uses aggressive memory allocation budget (up to 65% availMem or 30% totalMem) and Krita's sparse tile weighting.
 */
fun calculateRealMaxLayers(context: Context, width: Int, height: Int): Int {
    val w = max(64, width)
    val h = max(64, height)
    // 4 bytes per RGBA pixel + 10% tile/mipmap overhead in Krita
    val bytesPerLayer = (w.toLong() * h.toLong() * 4L * 1.10).toLong().coerceAtLeast(1024L)

    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager?.getMemoryInfo(memInfo)

    val availSysMem = memInfo.availMem
    val totalSysMem = memInfo.totalMem
    val maxHeap = Runtime.getRuntime().maxMemory()

    // Aggressive layer allocation budget: 65% of available RAM or 30% of total RAM, fallback to maxHeap
    val aggressiveBudget = if (totalSysMem > 0) {
        max(
            (availSysMem * 0.65).toLong(),
            (totalSysMem * 0.30).toLong()
        )
    } else {
        (maxHeap * 1.5).toLong()
    }

    val calculated = (aggressiveBudget / bytesPerLayer).toInt()
    return calculated.coerceIn(6, 300)
}

/**
 * Dynamically resolves physical device screen resolution.
 */
fun getDeviceScreenResolution(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width()
        val h = bounds.height()
        if (w > 0 && h > 0) return Pair(w, h)
    }
    val dm = context.resources.displayMetrics
    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
        return Pair(dm.widthPixels, dm.heightPixels)
    }
    return Pair(3000, 2120)
}

/**
 * Returns human-readable aspect ratio label.
 */
fun getAspectRatioLabel(w: Int, h: Int, context: Context? = null): String {
    if (w <= 0 || h <= 0) return ""
    // Specific standard paper / comic dimensions
    if ((w == 2150 && h == 3035) || (w == 3035 && h == 2150)) {
        return if (w <= h) context?.getString(R.string.create_ratio_b5_comic) ?: "B5 漫画" else context?.getString(R.string.create_ratio_b5_landscape) ?: "B5 横版"
    }
    if ((w == 2480 && h == 3508) || (w == 3508 && h == 2480)) {
        return if (w <= h) context?.getString(R.string.create_ratio_a4_paper) ?: "A4 纸张" else context?.getString(R.string.create_ratio_a4_landscape) ?: "A4 横版"
    }
    if ((w == 2550 && h == 3300) || (w == 3300 && h == 2550)) {
        return if (w <= h) context?.getString(R.string.create_ratio_us_letter) ?: "美制 Letter" else context?.getString(R.string.create_ratio_us_letter_landscape) ?: "美制 Letter 横版"
    }
    if ((w == 2550 && h == 4200) || (w == 4200 && h == 2550)) {
        return if (w <= h) context?.getString(R.string.create_ratio_us_legal) ?: "美制 Legal" else context?.getString(R.string.create_ratio_us_legal_landscape) ?: "美制 Legal 横版"
    }
    if ((w == 3300 && h == 5100) || (w == 5100 && h == 3300)) {
        return if (w <= h) context?.getString(R.string.create_ratio_us_tabloid) ?: "美制 Tabloid" else context?.getString(R.string.create_ratio_us_tabloid_landscape) ?: "美制 Tabloid 横版"
    }
    if ((w == 2175 && h == 3150) || (w == 3150 && h == 2175)) {
        return if (w <= h) context?.getString(R.string.create_ratio_us_executive) ?: "美制 Executive" else context?.getString(R.string.create_ratio_us_executive_landscape) ?: "美制 Executive 横版"
    }
    val ratio = w.toFloat() / h.toFloat()
    return when {
        abs(ratio - 1.0f) < 0.01f -> context?.getString(R.string.create_ratio_square) ?: "1:1 正方形"
        abs(ratio - 16f / 9f) < 0.02f -> context?.getString(R.string.create_ratio_widescreen) ?: "16:9 宽屏"
        abs(ratio - 9f / 16f) < 0.02f -> context?.getString(R.string.create_ratio_portrait_9_16) ?: "9:16 竖屏"
        abs(ratio - 4f / 3f) < 0.02f -> context?.getString(R.string.create_ratio_standard_4_3) ?: "4:3 标准"
        abs(ratio - 3f / 4f) < 0.02f -> context?.getString(R.string.create_ratio_portrait_3_4) ?: "3:4 竖屏"
        abs(ratio - 1080f / 2400f) < 0.02f -> context?.getString(R.string.create_ratio_wallpaper_20_9) ?: "20:9 手机壁纸"
        abs(ratio - 1080f / 4000f) < 0.02f -> context?.getString(R.string.create_ratio_webtoon) ?: "长图条漫"
        else -> {
            val gcdVal = gcd(w, h)
            val rw = w / gcdVal
            val rh = h / gcdVal
            if (rw in 1..20 && rh in 1..20) "$rw:$rh" else if (w >= h) context?.getString(R.string.create_ratio_landscape) ?: "横屏画幅" else context?.getString(R.string.create_ratio_portrait) ?: "竖屏画幅"
        }
    }
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return x
}

/**
 * SharedPreferences JSON storage for custom presets.
 */
object CustomPresetManager {
    private const val PREFS_NAME = "reverie_custom_presets"
    private const val KEY_PRESETS = "presets_json"

    fun loadPresets(context: Context): List<CanvasPresetItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        val list = mutableListOf<CanvasPresetItem>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CanvasPresetItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        defaultPpi = obj.optInt("ppi", 300),
                        description = obj.optString("desc", ""),
                        isCustom = true
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun savePresets(context: Context, presets: List<CanvasPresetItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = org.json.JSONArray()
        for (item in presets) {
            val obj = org.json.JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("width", item.width)
            obj.put("height", item.height)
            obj.put("ppi", item.defaultPpi)
            obj.put("desc", item.description)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }
}

fun getSystemPresets(context: Context): List<CanvasPresetItem> {
    val (screenW, screenH) = getDeviceScreenResolution(context)
    return listOf(
        CanvasPresetItem(
            id = "sys_screen",
            name = context.getString(R.string.create_preset_current_screen),
            width = screenW,
            height = screenH,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_current_screen_desc)
        ),
        CanvasPresetItem(
            id = "sys_square_2k",
            name = context.getString(R.string.create_preset_square_2k),
            width = 2048,
            height = 2048,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_square_2k_desc)
        ),
        CanvasPresetItem(
            id = "sys_square_4k",
            name = context.getString(R.string.create_preset_square_4k),
            width = 4096,
            height = 4096,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_square_4k_desc)
        ),
        CanvasPresetItem(
            id = "sys_16_9_4k",
            name = "4K UHD (16:9)",
            width = 3840,
            height = 2160,
            defaultPpi = 150,
            description = context.getString(R.string.create_preset_4k_landscape_desc)
        ),
        CanvasPresetItem(
            id = "sys_16_9_2k",
            name = "2K QHD (16:9)",
            width = 2560,
            height = 1440,
            defaultPpi = 100,
            description = context.getString(R.string.create_preset_2k_landscape_desc)
        ),
        CanvasPresetItem(
            id = "sys_16_9_fhd",
            name = context.getString(R.string.create_preset_fhd),
            width = 1920,
            height = 1080,
            defaultPpi = 72,
            description = context.getString(R.string.create_preset_fhd_desc)
        ),
        CanvasPresetItem(
            id = "sys_9_16_poster",
            name = context.getString(R.string.create_preset_poster_9_16),
            width = 1080,
            height = 1920,
            defaultPpi = 72,
            description = context.getString(R.string.create_preset_poster_9_16_desc)
        ),
        CanvasPresetItem(
            id = "sys_mobile_wallpaper",
            name = context.getString(R.string.create_preset_phone_wallpaper),
            width = 1080,
            height = 2400,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_phone_wallpaper_desc)
        ),
        CanvasPresetItem(
            id = "sys_a4_print",
            name = context.getString(R.string.create_preset_a4_print),
            width = 2480,
            height = 3508,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_a4_print_desc)
        ),
        CanvasPresetItem(
            id = "sys_us_letter",
            name = context.getString(R.string.create_preset_us_letter),
            width = 2550,
            height = 3300,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_us_letter_desc)
        ),
        CanvasPresetItem(
            id = "sys_us_legal",
            name = context.getString(R.string.create_preset_us_legal),
            width = 2550,
            height = 4200,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_us_legal_desc)
        ),
        CanvasPresetItem(
            id = "sys_us_tabloid",
            name = context.getString(R.string.create_preset_us_tabloid),
            width = 3300,
            height = 5100,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_us_tabloid_desc)
        ),
        CanvasPresetItem(
            id = "sys_us_executive",
            name = context.getString(R.string.create_preset_us_executive),
            width = 2175,
            height = 3150,
            defaultPpi = 300,
            description = context.getString(R.string.create_preset_us_executive_desc)
        ),
        CanvasPresetItem(
            id = "sys_b5_comic",
            name = context.getString(R.string.create_preset_b5_comic),
            width = 2150,
            height = 3035,
            defaultPpi = 350,
            description = context.getString(R.string.create_preset_b5_comic_desc)
        ),
        CanvasPresetItem(
            id = "sys_strip_comic",
            name = context.getString(R.string.create_preset_webtoon),
            width = 1080,
            height = 4000,
            defaultPpi = 150,
            description = context.getString(R.string.create_preset_webtoon_desc)
        )
    )
}
