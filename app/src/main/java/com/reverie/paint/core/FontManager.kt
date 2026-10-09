/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.reverie.paint.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 字体项模型
 * @param id 唯一标识 (如 "system:default", "custom_myfont.ttf")
 * @param displayName 显示名称
 * @param filePath 本地文件路径 (自定义导入字体为实际绝对路径，系统字体为 null)
 * @param isCustom 是否为用户导入的外部字体
 */
data class FontItem(
    val id: String,
    val displayName: String,
    val filePath: String? = null,
    val isCustom: Boolean = false,
)

/**
 * 字体管理器 (统一管理系统内置预设与用户导入的外部 TTF/OTF 字体)
 */
object FontManager {
    private const val PREFS_NAME = "font_manager_prefs"
    private const val KEY_CUSTOM_FONTS = "custom_fonts_json"
    private const val FONTS_DIR_NAME = "custom_fonts"

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    var appContext: Context? = null

    private val SYSTEM_SERIF_CJK_PATHS = listOf(
        "/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSerifCJKjp-Regular.otc",
        "/system/fonts/NotoSerifCJK-Regular.otf",
        "/system/fonts/SourceHanSerifCN-Regular.otf",
        "/system/fonts/Oplus-Serif.ttf",
        "/system/fonts/SysFont-Hans-Regular.ttf",
    )

    fun getPresets(context: Context): List<FontItem> {
        return listOf(
            FontItem(id = "system:default", displayName = context.getString(R.string.typography_font_default)),
            FontItem(id = "bundled:lxgw_wenkai", displayName = context.getString(R.string.typography_font_lxgw_wenkai)),
            FontItem(id = "system:serif", displayName = context.getString(R.string.typography_font_serif)),
            FontItem(id = "system:monospace", displayName = context.getString(R.string.typography_font_monospace)),
            FontItem(id = "system:cursive", displayName = context.getString(R.string.typography_font_cursive)),
            FontItem(id = "system:sans", displayName = context.getString(R.string.typography_font_sans)),
        )
    }

    fun loadCustomFonts(context: Context): List<FontItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_FONTS, null) ?: return emptyList()
        val list = mutableListOf<FontItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val path = obj.optString("path")
                if (id.isNotEmpty() && path.isNotEmpty()) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        list.add(FontItem(id = id, displayName = name, filePath = path, isCustom = true))
                    }
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    private fun saveCustomFonts(context: Context, fonts: List<FontItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (f in fonts.filter { it.isCustom && it.filePath != null }) {
            val obj = JSONObject()
            obj.put("id", f.id)
            obj.put("name", f.displayName)
            obj.put("path", f.filePath)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_FONTS, arr.toString()).apply()
    }

    /**
     * 从 URI 导入用户字体并拷贝到应用内部沙盒存储
     */
    fun importFontFromUri(context: Context, uri: Uri): FontItem? {
        val contentResolver = context.contentResolver
        var originalName = "CustomFont"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val n = cursor.getString(nameIndex)
                    if (!n.isNullOrBlank()) originalName = n
                }
            }
        } catch (_: Throwable) {}

        val fontsDir = File(context.filesDir, FONTS_DIR_NAME).apply { mkdirs() }
        val ext = when {
            originalName.endsWith(".otf", ignoreCase = true) -> ".otf"
            originalName.endsWith(".ttc", ignoreCase = true) -> ".ttc"
            else -> ".ttf"
        }
        val safeBaseName = originalName.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
        val targetFile = File(fontsDir, "${safeBaseName}_${System.currentTimeMillis()}$ext")

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val tf = Typeface.createFromFile(targetFile) ?: run {
                targetFile.delete()
                return null
            }

            val fontId = "custom_${targetFile.name}"
            typefaceCache[targetFile.absolutePath] = tf
            val displayName = originalName.substringBeforeLast(".")

            val existing = loadCustomFonts(context).toMutableList()
            val newItem = FontItem(id = fontId, displayName = displayName, filePath = targetFile.absolutePath, isCustom = true)
            existing.add(newItem)
            saveCustomFonts(context, existing)
            return newItem
        } catch (_: Throwable) {
            targetFile.delete()
            return null
        }
    }

    /**
     * 删除已导入的自定义字体
     */
    fun deleteCustomFont(context: Context, font: FontItem) {
        if (!font.isCustom || font.filePath == null) return
        try {
            File(font.filePath).delete()
            typefaceCache.remove(font.filePath)
            val list = loadCustomFonts(context).filter { it.id != font.id }
            saveCustomFonts(context, list)
        } catch (_: Throwable) {}
    }

    /**
     * 解析并获取 Typeface 实例
     */
    fun getTypeface(family: String, fontPath: String?, context: Context? = null): Typeface? {
        if (!fontPath.isNullOrBlank()) {
            typefaceCache[fontPath]?.let { return it }
            val file = File(fontPath)
            if (file.exists() && file.canRead()) {
                try {
                    val tf = Typeface.createFromFile(file)
                    if (tf != null) {
                        typefaceCache[fontPath] = tf
                        return tf
                    }
                } catch (_: Throwable) {}
            }
        }
        if (family == "bundled:lxgw_wenkai" || family == "霞鹜文楷") {
            typefaceCache["bundled:lxgw_wenkai"]?.let { return it }
            val ctx = context ?: appContext
            if (ctx != null) {
                try {
                    val tf = Typeface.createFromAsset(ctx.assets, "fonts/LXGWWenKaiLite-Regular.ttf")
                    if (tf != null) {
                        typefaceCache["bundled:lxgw_wenkai"] = tf
                        return tf
                    }
                } catch (_: Throwable) {}
            }
        }
        if (family == "衬线体" || family == "衬线" || family == "serif" || family == "system:serif" || family == "system:serif_cjk") {
            typefaceCache["system:serif_cjk"]?.let { return it }
            for (p in SYSTEM_SERIF_CJK_PATHS) {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    try {
                        val tf = Typeface.createFromFile(f)
                        if (tf != null) {
                            typefaceCache["system:serif_cjk"] = tf
                            return tf
                        }
                    } catch (_: Throwable) {}
                }
            }
            return Typeface.SERIF
        }
        return when (family) {
            "等宽体", "等宽", "monospace", "system:monospace" -> Typeface.MONOSPACE
            "手写体", "手写", "cursive", "system:cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            "无衬线体", "黑体", "sans-serif", "system:sans" -> Typeface.SANS_SERIF
            "系统默认", "default", "system:default" -> Typeface.DEFAULT
            else -> null
        }
    }

    /**
     * 获取字体项对应的 Typeface 用于 UI 实时预览
     */
    fun getTypefaceForItem(context: Context, font: FontItem): Typeface {
        return getTypeface(font.id, font.filePath, context) ?: Typeface.DEFAULT
    }

    /**
     * 解析字体的用户可读名称
     */
    fun resolveDisplayName(context: Context, familyId: String, fontPath: String?): String {
        if (!fontPath.isNullOrBlank()) {
            val file = File(fontPath)
            val customFonts = loadCustomFonts(context)
            val match = customFonts.firstOrNull { it.id == familyId || it.filePath == fontPath }
            if (match != null) return match.displayName
            return file.nameWithoutExtension.substringBeforeLast("_")
        }
        return when (familyId) {
            "bundled:lxgw_wenkai", "霞鹜文楷" -> context.getString(R.string.typography_font_lxgw_wenkai)
            "衬线体", "衬线", "serif", "system:serif", "system:serif_cjk" -> context.getString(R.string.typography_font_serif)
            "等宽体", "等宽", "monospace", "system:monospace" -> context.getString(R.string.typography_font_monospace)
            "手写体", "手写", "cursive", "system:cursive" -> context.getString(R.string.typography_font_cursive)
            "无衬线体", "黑体", "sans-serif", "system:sans" -> context.getString(R.string.typography_font_sans)
            else -> context.getString(R.string.typography_font_default)
        }
    }
}
