/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.ResourceDirs
import com.reverie.paint.core.brushstore.ResourceStore
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

    internal fun PaintViewModel.shareBrushPreset(context: android.content.Context, presetId: String): Boolean {
        val presetName = BrushStoreVm.resolveEntry(presetId)?.name ?: run {
            android.widget.Toast.makeText(context, context.getString(R.string.brush_share_failed, presetId), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val dir = ResourceDirs.presets(appContext.filesDir)
            val brushDir = ResourceDirs.tips(appContext.filesDir)
            // W-11: 只读取有效字节写入 cache，保护源文件
            val assets = BrushExportSource.assetReader(appContext)
            val exp = BrushExportSource.effective(presetId, dir, assets)
            if (exp == null) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.brush_share_failed, presetName),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return false
            }
            val stagingDir = File(appContext.cacheDir, "export_staging")
            val srcFile = BrushExportSource.stage(presetName, exp, stagingDir)

            // If the preset has a custom or separate tip asset, export as .bundle so the tip pattern is preserved
            val tip = exp.tipName?.takeIf { it.isNotBlank() }?.let { BrushExportSource.tipFile(it, stagingDir, brushDir, assets) }
            val patterns = BrushExportSource.patternFiles(exp, stagingDir, ResourceDirs.patterns(appContext.filesDir), assets)
            if (tip != null || patterns.isNotEmpty()) {
                val group = userBrushGroups[presetId] ?: "默认"
                val bundleFile = KritaBundleManager.exportBundle(
                    context = context,
                    bundleName = presetName,
                    groupName = group,
                    presets = listOf(presetName to srcFile),
                    tipAssets = listOfNotNull(tip),
                    patternAssets = patterns,
                )
                return KritaBundleManager.shareFile(
                    context = context,
                    file = bundleFile,
                    mimeType = "application/x-krita-resourcebundle",
                    title = context.getString(R.string.brush_share_title, presetName),
                )
            }

            // Standard procedural/algorithm brush without external tip: share the single file (.kpp, or raw .myb)
            val exportDir = File(appContext.cacheDir, "export_brushes").apply { if (!exists()) mkdirs() }
            val exportFile = File(exportDir, srcFile.name)
            srcFile.copyTo(exportFile, overwrite = true)

            KritaBundleManager.shareFile(
                context = context,
                file = exportFile,
                mimeType = if (exp.ext == "myb") "application/octet-stream" else "application/x-krita-paintoppreset",
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

        val dir = ResourceDirs.presets(appContext.filesDir)
        val brushDir = ResourceDirs.tips(appContext.filesDir)

        val presetsToPack = mutableListOf<Pair<String, File>>()
        val tipAssetsToPack = mutableListOf<File>()
        val patternAssetsToPack = mutableListOf<File>()

        val assets = BrushExportSource.assetReader(appContext)
        val staging = File(appContext.cacheDir, "export_staging/group").apply { deleteRecursively(); mkdirs() }
        for (p in targetPresets) {
            // W-11: 有效字节写入 cache 再次保护源文件只读
            val exp = BrushExportSource.effective(p.id, dir, assets) ?: continue
            presetsToPack.add(p.name to BrushExportSource.stage(p.name, exp, staging))
            val tip = exp.tipName?.takeIf { it.isNotBlank() }?.let { BrushExportSource.tipFile(it, staging, brushDir, assets) }
            if (tip != null && tipAssetsToPack.none { it.name == tip.name }) tipAssetsToPack.add(tip)
            BrushExportSource.patternFiles(exp, staging, ResourceDirs.patterns(appContext.filesDir), assets).forEach { pf ->
                if (patternAssetsToPack.none { it.name == pf.name }) patternAssetsToPack.add(pf)
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
                patternAssets = patternAssetsToPack,
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
        if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly) return false
        // Native-table index (BrushPresetInfo.index), not list position.
        val preset = brushPresets.firstOrNull { it.index == presetIndex } ?: return false
        if (preset.isBuiltIn) {
            return false // 内置笔刷固定名称，禁止修改
        }
        val clean = newName.trim().ifEmpty { return false }
        if (clean == preset.name) return true
        val dir = ResourceDirs.presets(appContext.filesDir)
        // W-08：不允许改成已有预设名（任意扩展名）；.kpp / .myb 同一条路
        if (!com.reverie.paint.core.brushstore.BrushImportPipeline.rename(preset.id, clean, dir)) return false
        // 偏好按 id 存：id 在改名后不变，分组 / 排序 / 常用 / 最近 / 快捷栏都不需要跟着改
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

            val storeRoot = com.reverie.paint.core.brushstore.BrushStoreBootstrap.storeRoot(appContext.filesDir)
            val store = com.reverie.paint.core.brushstore.BrushStoreBootstrap.resourceStore
                ?: com.reverie.paint.core.brushstore.ResourceStore(storeRoot)

            val cleanName: String
            if (isKritaNative) {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                val ref = store.register(bytes, com.reverie.paint.core.brushstore.ResourceStore.Kind.TIP, "$baseName.$ext")
                cleanName = ref.file
            } else {
                // Decode any image format and write as standard lossless PNG for KisPngBrush
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val ref = store.register(baos.toByteArray(), com.reverie.paint.core.brushstore.ResourceStore.Kind.TIP, "$baseName.png")
                cleanName = ref.file
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

            val storeRoot = com.reverie.paint.core.brushstore.BrushStoreBootstrap.storeRoot(appContext.filesDir)
            val store = com.reverie.paint.core.brushstore.BrushStoreBootstrap.resourceStore
                ?: com.reverie.paint.core.brushstore.ResourceStore(storeRoot)

            val cleanName: String
            if (isPat) {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                val ref = store.register(bytes, com.reverie.paint.core.brushstore.ResourceStore.Kind.PATTERN, "$baseName.pat")
                cleanName = ref.file
            } else {
                val bmp = resolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return null
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val ref = store.register(baos.toByteArray(), com.reverie.paint.core.brushstore.ResourceStore.Kind.PATTERN, "$baseName.png")
                cleanName = ref.file
            }
            updateBrushTexturePattern(cleanName)
            cleanName
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importCustomPattern failed", e)
            null
        }
    }

    /** 导入用户自定义图片作为指定笔刷的缩略图并保存 */
    internal fun PaintViewModel.importPresetThumbnail(uri: android.net.Uri, targetPresetIndex: Int = brushPresetIndex): Boolean {
        return try {
            val bmp = ImageImportHelper.decodeUriSafely(appContext, uri, maxDimension = 512)
            if (bmp == null) {
                android.util.Log.e("ReveriePaint", "importPresetThumbnail: failed to decode image from uri=$uri")
                return false
            }

            val targetSize = 256
            val srcW = bmp.width
            val srcH = bmp.height
            val minDim = minOf(srcW, srcH)
            val left = (srcW - minDim) / 2
            val top = (srcH - minDim) / 2
            val cropped = Bitmap.createBitmap(bmp, left, top, minDim, minDim)
            val squareBmp = if (minDim == targetSize) {
                cropped
            } else {
                val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
                if (scaled != cropped) cropped.recycle()
                scaled
            }
            if (bmp != squareBmp && !bmp.isRecycled && bmp != cropped) {
                bmp.recycle()
            }
            capturePresetThumbnail(squareBmp, targetPresetIndex)
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "importPresetThumbnail failed", e)
            false
        }
    }



