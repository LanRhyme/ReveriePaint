/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import androidx.core.content.FileProvider
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

    internal fun PaintViewModel.shareBrushPreset(context: android.content.Context, presetName: String): Boolean {
        return try {
            val dir = File(appContext.filesDir, "paintoppresets")
            val brushDir = File(appContext.filesDir, "brushes")
            val srcFile = File(dir, "$presetName.kpp")
            if (!srcFile.exists()) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    appContext.assets.open("paintoppresets/$presetName.kpp").use { input ->
                        srcFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            }
            if (!srcFile.exists()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.brush_share_failed, presetName),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return false
            }

            // Sync latest parameters into .kpp file
            val params = brushParams[presetName]
            if (params != null) {
                KppHelper.updateKppFile(srcFile, presetName, params)
            }

            val tipName = params?.tipAsset?.ifBlank { null }
                ?: KppHelper.extractTipAssetFilename(srcFile.readBytes())

            // If the preset has a custom or separate tip asset, export as .bundle so the tip pattern is preserved
            if (!tipName.isNullOrBlank()) {
                val tipFile = File(brushDir, tipName)
                if (!tipFile.exists()) {
                    try {
                        appContext.assets.open("brushes/$tipName").use { inS ->
                            tipFile.outputStream().use { inS.copyTo(it) }
                        }
                    } catch (_: Exception) {}
                }
                if (tipFile.exists()) {
                    val group = userBrushGroups[presetName] ?: "分享"
                    val bundleFile = KritaBundleManager.exportBundle(
                        context = context,
                        bundleName = presetName,
                        groupName = group,
                        presets = listOf(presetName to srcFile),
                        tipAssets = listOf(tipFile),
                    )
                    return KritaBundleManager.shareFile(
                        context = context,
                        file = bundleFile,
                        mimeType = "application/x-krita-resourcebundle",
                        title = context.getString(R.string.brush_share_title, presetName),
                    )
                }
            }

            // Standard procedural/algorithm brush without external tip: share .kpp
            val exportDir = File(appContext.cacheDir, "export_brushes").apply { if (!exists()) mkdirs() }
            val exportFile = File(exportDir, "$presetName.kpp")
            srcFile.copyTo(exportFile, overwrite = true)

            KritaBundleManager.shareFile(
                context = context,
                file = exportFile,
                mimeType = "application/x-krita-paintoppreset",
                title = context.getString(R.string.brush_share_title, presetName),
            )
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "shareBrushPreset failed", e)
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.brush_share_failed, presetName),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    /** Export an entire brush group as a standard Krita .bundle file */
    internal fun PaintViewModel.exportBrushGroup(context: android.content.Context, groupName: String): Boolean {
        val targetPresets = if (groupName == "全部") {
            brushPresets
        } else {
            brushPresets.filter { it.group == groupName }
        }
        if (targetPresets.isEmpty()) {
            android.widget.Toast.makeText(context, context.getString(R.string.brush_group_empty_export), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }

        val dir = File(appContext.filesDir, "paintoppresets")
        val brushDir = File(appContext.filesDir, "brushes")

        val presetsToPack = mutableListOf<Pair<String, File>>()
        val tipAssetsToPack = mutableListOf<File>()

        for (p in targetPresets) {
            val file = File(dir, "${p.name}.kpp")
            if (!file.exists()) {
                try {
                    appContext.assets.open("paintoppresets/${p.name}.kpp").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                } catch (_: Exception) {}
            }
            if (file.exists()) {
                val params = brushParams[p.name]
                if (params != null) {
                    KppHelper.updateKppFile(file, p.name, params)
                }
                presetsToPack.add(p.name to file)

                val tipName = params?.tipAsset?.ifBlank { null }
                    ?: KppHelper.extractTipAssetFilename(file.readBytes())
                if (!tipName.isNullOrBlank()) {
                    val tipFile = File(brushDir, tipName)
                    if (!tipFile.exists()) {
                        try {
                            appContext.assets.open("brushes/$tipName").use { input ->
                                tipFile.outputStream().use { input.copyTo(it) }
                            }
                        } catch (_: Exception) {}
                    }
                    if (tipFile.exists() && tipAssetsToPack.none { it.name == tipFile.name }) {
                        tipAssetsToPack.add(tipFile)
                    }
                }
            }
        }

        if (presetsToPack.isEmpty()) {
            android.widget.Toast.makeText(context, context.getString(R.string.brush_group_empty_export), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val bundleFile = KritaBundleManager.exportBundle(
                context = context,
                bundleName = groupName,
                groupName = groupName,
                presets = presetsToPack,
                tipAssets = tipAssetsToPack,
            )
            KritaBundleManager.shareFile(
                context = context,
                file = bundleFile,
                mimeType = "application/x-krita-resourcebundle",
                title = context.getString(R.string.brush_share_group_title, groupName),
            )
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "exportBrushGroup failed", e)
            android.widget.Toast.makeText(context, context.getString(R.string.brush_export_group_failed), android.widget.Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Picks the brush size to show when a preset is selected.
     *
     * The engine can report a literal `1.0` that does **not** mean "1 pixel". Two paths produce
     * it: `KisBrushBasedPaintOpSettings::paintOpSize()` returns
     * `KIS_SAFE_ASSERT_RECOVER_RETURN_VALUE(this->brush(), 1.0)` when the brush failed to parse,
     * and the `auto_brush` that `KisBrush::fromXML` falls back to has no `<MaskGenerator>`, whose
     * diameter default happens to be `1.0` as well (`KisAutoBrushFactory` reads
     * `attr("diameter", "1.0")`). Either way "the brush did not resolve" is reported as 1px, and
     * because 1.0 satisfies the generic `size > 0` guard the UI happily parks at the minimum —
     * which is exactly why every imported brush had to be re-adjusted by hand.
     *
     * Only when the engine value is <= 1.0 **and** we actually remember a size > 1.0 for this
     * preset do we substitute the remembered one. A user who deliberately set 1px is not
     * overridden, and built-in presets (no remembered entry) behave exactly as before.
     */

    internal fun PaintViewModel.importBrushFromUri(
        uri: android.net.Uri,
        targetGroup: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ): Boolean {
        viewModelScope.launch(Dispatchers.IO) {
            val result = importSingleBrushInternal(uri, targetGroup)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    if (result.groupName != null) {
                        brushPanelSelectedCategory = result.groupName
                    }
                    reloadBrushPresets(selectName = result.presetName)
                }
                if (onComplete != null) {
                    onComplete(result.success)
                } else {
                    val toastMsg = if (result.success) {
                        if (result.count > 1 && result.groupName != null) {
                            appContext.getString(R.string.brush_import_abr_success, result.count, result.groupName)
                        } else {
                            appContext.getString(R.string.brush_studio_toast_imported)
                        }
                    } else {
                        appContext.getString(R.string.brush_studio_toast_import_failed)
                    }
                    android.widget.Toast.makeText(
                        appContext,
                        toastMsg,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        return true
    }

    /** 批量导入外部笔刷文件列表 */
    internal fun PaintViewModel.importBrushesFromUris(
        uris: List<android.net.Uri>,
        targetGroup: String? = null,
        onComplete: ((Boolean, Int) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var lastPreset: String? = null
            var lastGroup: String? = targetGroup

            for (u in uris) {
                val res = importSingleBrushInternal(u, targetGroup)
                if (res.success) {
                    successCount++
                    if (res.presetName != null) lastPreset = res.presetName
                    if (res.groupName != null) lastGroup = res.groupName
                }
            }

            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    if (lastGroup != null) {
                        brushPanelSelectedCategory = lastGroup
                    }
                    reloadBrushPresets(selectName = lastPreset)
                }
                onComplete?.invoke(successCount > 0, successCount)
            }
        }
    }

    /** 重命名笔刷 (内置笔刷固定禁止重命名) */
    internal fun PaintViewModel.renameBrushPreset(presetIndex: Int, newName: String): Boolean {
        // Native-table index (BrushPresetInfo.index), not list position.
        val preset = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷固定名称，禁止修改
        }
        val clean = newName.trim().ifEmpty { return false }
        if (clean == preset.name) return true
        val dir = File(appContext.filesDir, "paintoppresets")
        val src = File(dir, "${preset.name}.kpp")
        val dst = File(dir, "$clean.kpp")
        if (src.exists()) src.renameTo(dst)
        val p = brushParams.remove(preset.name)
        if (p != null) brushParams[clean] = p
        persistBrushParams()
        val g = userBrushGroups[preset.name]
        if (g != null) {
            userBrushGroups = (userBrushGroups - preset.name) + (clean to g)
        }
        saveBrushGroups()
        if (brushOrder.contains(preset.name)) {
            brushOrder = brushOrder.map { if (it == preset.name) clean else it }
            saveBrushOrder()
        }
        reloadBrushPresets(selectName = clean)
        return true
    }

    /** 导入用户自定义笔尖贴图 (PNG, GBR, GIH, JPG) 并设置为当前笔刷笔尖 */
    internal fun PaintViewModel.importCustomBrushTip(uri: android.net.Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val rawName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "tip_${System.currentTimeMillis()}"

            val baseName = if (rawName.contains('.')) rawName.substringBeforeLast(".") else rawName
            val ext = if (rawName.contains('.')) rawName.substringAfterLast(".").lowercase() else "png"
            val isKritaNative = ext == "gbr" || ext == "gih" || ext == "svg"

            val brushDir = File(appContext.filesDir, "brushes")
            if (!brushDir.exists()) brushDir.mkdirs()

            val cleanName: String
            if (isKritaNative) {
                cleanName = "$baseName.$ext"
                val target = File(brushDir, cleanName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // Decode any image format and write as standard lossless PNG for KisPngBrush
                cleanName = "$baseName.png"
                val target = File(brushDir, cleanName)
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                target.outputStream().use { output ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            updateBrushTipAsset(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomBrushTip failed", e)
            null
        }
    }

    /** 导入用户自定义材质纹理贴图 (.pat, .png, .jpg) 并设置为当前笔刷纹理 */
    internal fun PaintViewModel.importCustomPattern(uri: android.net.Uri): String? {
        return try {
            val resolver = appContext.contentResolver
            val rawName = runCatching {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast("/") ?: "pat_${System.currentTimeMillis()}"

            val baseName = if (rawName.contains('.')) rawName.substringBeforeLast(".") else rawName
            val ext = if (rawName.contains('.')) rawName.substringAfterLast(".").lowercase() else "png"
            val isPat = ext == "pat"

            val patternDir = File(appContext.filesDir, "patterns")
            if (!patternDir.exists()) patternDir.mkdirs()

            val cleanName: String
            if (isPat) {
                cleanName = "$baseName.pat"
                val target = File(patternDir, cleanName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                cleanName = "$baseName.png"
                val target = File(patternDir, cleanName)
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                target.outputStream().use { output ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
            }
            updateBrushTexturePattern(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomPattern failed", e)
            null
        }
    }



