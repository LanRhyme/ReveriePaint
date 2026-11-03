/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.ResourceDirs
import com.reverie.paint.core.brushstore.ResourceStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.resume

private var cachedBuiltInNames: Set<String>? = null

internal fun PaintViewModel.getBuiltInBrushNames(): Set<String> {
    cachedBuiltInNames?.let { return it }
    val names = try {
        appContext.assets.list("paintoppresets")
            ?.filter { com.reverie.paint.core.brushstore.BrushImportPipeline.isPreset(it) }
            ?.map { it.substringBeforeLast('.') }?.toSet() ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }
    if (names.isNotEmpty()) {
        cachedBuiltInNames = names
    }
    return names
}

internal fun PaintViewModel.loadBrushPresets(force: Boolean = false) {
    if (brushPresetsLoaded && brushPresets.isNotEmpty() && !force) return
    if (isBrushPresetsLoading && !force) return
    isBrushPresetsLoading = true
    loadToolOptions()
    loadViewSettings()
    loadShortcuts()
    loadToolBrushStates()
    // 把 assets 里几百个笔刷预设与笔刷资源 (.gbr/.gih/.png/.svg, 可达几十 MB)
    // 拷进 filesDir 是纯 IO: 原先同步跑在调用线程 (MainActivity 的
    // LaunchedEffect, 即主线程), 首启或清数据之后的启动卡顿主要来自这里。
    // 改为 IO 线程执行, 完成后再回主线程走后面的流程 (Compose 状态写入必须在
    // 主线程, JNI 读取仍在渲染线程)。
    viewModelScope.launch {
        try {
            val dirs = withContext(Dispatchers.IO) {
                com.reverie.paint.core.brushstore.BrushDiagnostics.init(appContext.filesDir)
                val d = copyBundledBrushAssets()
                bootstrapBrushStore(d.first)
                d
            }
            loadBrushPresetsAfterAssets(dirs.first, dirs.second)
        } catch (t: Throwable) {
            android.util.Log.e("ReveriePaint", "loadBrushPresets IO failed", t)
            isBrushPresetsLoading = false
            brushPresetsLoaded = false
            if (currentPage == Page.PAINTING && isBlockingLoading &&
                blockingLoadingMessage == getString(R.string.brush_presets_loading)
            ) {
                isBlockingLoading = false
            }
        }
    }
}

/**
 * 启动时初始化笔刷存储（快照 + 覆盖）。
 * 失败时 [com.reverie.paint.core.brushstore.BrushStoreBootstrap.readOnly] 为 true，VM 进入只读兜底。
 */
private fun PaintViewModel.bootstrapBrushStore(presetDir: File) {
    val ctx = appContext
    val stamp = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${com.reverie.paint.BuildConfig.VERSION_CODE}:${pi.lastUpdateTime}"
    } catch (_: Throwable) {
        "${com.reverie.paint.BuildConfig.VERSION_CODE}"
    }
    com.reverie.paint.core.brushstore.BrushStoreBootstrap.run(
        filesDir = ctx.filesDir,
        presetDir = presetDir,
        installStamp = stamp,
        readAssets = {
            val am = ctx.assets
            (am.list("paintoppresets") ?: emptyArray())
                .filter { com.reverie.paint.core.brushstore.BrushStoreBootstrap.isFactoryPreset(it) }
                .associateWith { n -> am.open("paintoppresets/$n").use { it.readBytes() } }
        },
        migrate = {
            migrateLegacyBrushParams(presetDir).also {
                toolBrushStates = com.reverie.paint.core.brushstore.ToolMemoryMigration.run(toolBrushStates) {
                    toolBrushStates = it
                    persistToolBrushStates()
                }
            }
        },
    )
}

/** W-07：首次进入时把旧 legacyParams 迁成覆盖；成功后写 schema，之后直接返回 true */
private fun PaintViewModel.migrateLegacyBrushParams(presetDir: File): Boolean {
    val ctx = appContext
    val fromSchema = com.reverie.paint.core.brushstore.LegacyBrushParamsMigrator.migratedSchema(ctx)
    if (fromSchema >= com.reverie.paint.core.brushstore.LegacyBrushParamsMigrator.CURRENT_SCHEMA) return true
    val legacy = loadBrushParams()
    val raw = prefs().getString("brush_params", null)
    val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    com.reverie.paint.core.brushstore.LegacyBrushParamsMigrator.run(
        com.reverie.paint.core.brushstore.BrushStoreBootstrap.storeRoot(ctx.filesDir),
        presetDir,
        legacy,
        raw,
        ts,
        fromSchema = fromSchema,
    )
    com.reverie.paint.core.brushstore.LegacyBrushParamsMigrator.setMigratedSchema(ctx, com.reverie.paint.core.brushstore.LegacyBrushParamsMigrator.CURRENT_SCHEMA)
    return true
}

/**
 * assets -> filesDir 的一次性拷贝 (纯 IO; 已存在的文件直接跳过, 可重复调用)。
 * 返回 (预设目录, 笔刷资源目录)。
 */
private fun PaintViewModel.copyBundledBrushAssets(): Pair<File, File> {
    val dir = ResourceDirs.presets(appContext.filesDir)
    val brushDir = ResourceDirs.tips(appContext.filesDir)
    val patternDir = ResourceDirs.patterns(appContext.filesDir)
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val lastInstalledVersion = prefs.getInt("brush_assets_installed_version", -1)
    val isVersionMatched = lastInstalledVersion == com.reverie.paint.BuildConfig.VERSION_CODE

    val assets = appContext.assets
    val brushAssetNames = assets.list("brushes") ?: emptyArray()
    val patternAssetNames = assets.list("patterns") ?: emptyArray()

    // 缓存出厂素材清单供备份过滤使用（避免将出厂笔尖与纹理打包）
    val storeRoot = com.reverie.paint.core.brushstore.BrushStoreBootstrap.storeRoot(appContext.filesDir)
    val factoryResFile = File(storeRoot, com.reverie.paint.core.backup.BrushBackupMerge.FACTORY_RESOURCES_FILE)
    if (!factoryResFile.exists() || !isVersionMatched) {
        runCatching {
            val presetNames = assets.list("paintoppresets")?.filter {
                com.reverie.paint.core.brushstore.BrushImportPipeline.isPreset(it)
            } ?: emptyList()
            com.reverie.paint.core.backup.BrushBackupMerge.saveFactoryResources(
                appContext.filesDir,
                brushAssetNames.toList(),
                patternAssetNames.toList(),
                presetNames,
            )
        }
    }

    // 若当前版本已拷贝过资源且目标目录健全，直接秒级快速返回，免去对 500+ 个 assets 文件的解压与遍历检查
    if (isVersionMatched && dir.exists() && brushDir.exists() && patternDir.exists()) {
        val presetCount = dir.list()?.size ?: 0
        val brushCount = brushDir.list()?.size ?: 0
        if (presetCount >= 100 && brushCount >= 100) {
            return dir to brushDir
        }
    }

    if (!dir.exists()) dir.mkdirs()
    if (!brushDir.exists()) brushDir.mkdirs()
    if (!patternDir.exists()) patternDir.mkdirs()

    // kpp 预设由 materializeAll 生成，出厂 tip/pattern 统一经 ResourceStore 登记
    try {
        for (name in brushAssetNames) {
            val bytes = assets.open("brushes/$name").use { it.readBytes() }
            com.reverie.paint.core.brushstore.BrushStoreBootstrap.resourceStore(appContext.filesDir).register(
                bytes,
                com.reverie.paint.core.brushstore.ResourceStore.Kind.TIP,
                name,
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("ReveriePaint", "brush copy failed", e)
    }

    try {
        for (name in patternAssetNames) {
            val bytes = assets.open("patterns/$name").use { it.readBytes() }
            com.reverie.paint.core.brushstore.BrushStoreBootstrap.resourceStore(appContext.filesDir).register(
                bytes,
                com.reverie.paint.core.brushstore.ResourceStore.Kind.PATTERN,
                name,
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("ReveriePaint", "pattern copy failed", e)
    }

    try {
        prefs.edit().putInt("brush_assets_installed_version", com.reverie.paint.BuildConfig.VERSION_CODE).apply()
    } catch (_: Exception) {
    }
    return dir to brushDir
}

/** [loadBrushPresets] 的后续流程 (依赖 assets 已就位, 在主线程执行) */
private fun PaintViewModel.loadBrushPresetsAfterAssets(
    dir: File,
    brushDir: File,
) {
    // Restore persisted user brush groups and custom order
    loadBrushGroups()
    loadCategoryOrder()
    // 分组 / 排序 / 常用 / 最近 / 快捷栏顺序按 id 存；旧名字键在这里（identity 已就绪后）一次性迁移
    loadBrushIdPrefs()
    // Build the list on the render thread (JNI reads), but assign the
    // Compose state on the MAIN thread: mutableStateOf written from the
    // render HandlerThread is not reliably visible to composition.
    val list = ArrayList<BrushPresetInfo>()
    runCore(after = {
        try {
            android.util.Log.d("ReveriePaint", "loadBrushPresets assign=${list.size}")
            // Re-apply the persisted user order (same policy as reloadBrushPresets)
            val rank = brushOrder.withIndex().associate { it.value to it.index }
            val ordered =
                if (rank.isEmpty()) list.toList()
                else list.toList().sortedBy { rank[it.id] ?: (rank.size + it.index) }
            brushPresets = ordered
            brushPresetsLoaded = true
            if (ordered.isNotEmpty()) {
                val savedToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
                applyTool(savedToolId, force = true)
            }
            if (currentPage == Page.PAINTING && isBlockingLoading &&
                blockingLoadingMessage == getString(R.string.brush_presets_loading)
            ) {
                isBlockingLoading = false
                frontBufferQuietUntilMs = FrontBufferQuiet.onDocumentLoaded(
                    android.os.SystemClock.uptimeMillis(),
                    PaintViewModel.NEW_CANVAS_FRONT_BUFFER_QUIET_MS,
                )
            }
        } finally {
            isBrushPresetsLoading = false
        }
    }) {
        try {
            android.util.Log.d("ReveriePaint", "loadBrushPresets runCore start")
            val nrb = ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            android.util.Log.d("ReveriePaint", "loadBrushResources count=$nrb")
            val patternDir = ResourceDirs.patterns(appContext.filesDir)
            if (patternDir.exists()) {
                try {
                    ReverieCoreBridge.loadPatternResources(patternDir.absolutePath)
                } catch (_: Throwable) {
                }
            }
            val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
            android.util.Log.d("ReveriePaint", "loadBrushPresets count=$n")
            val builtInNames = getBuiltInBrushNames()
            list.clear()
            list.addAll(scanNativePresets(n, builtInNames))
            android.util.Log.d("ReveriePaint", "loadBrushPresets list=${list.size}")
        } catch (t: Throwable) {
            android.util.Log.e("ReveriePaint", "loadBrushPresets runCore op failed", t)
        }
    }
}

/**
 * 引擎刚加载完预设表后，把它读成 UI 列表的唯一入口（启动加载与 reloadBrushPresets 共用）。
 * 引擎只认磁盘文件名，所以"文件名 → id"在这里做且只在这里做一次（[BrushStoreVm.idForScanned]）；
 * 之后 UI / VM 一律用 [BrushPresetInfo.id]。
 */
internal fun PaintViewModel.scanNativePresets(n: Int, builtInNames: Set<String>): List<BrushPresetInfo> =
    (0 until n).map { i ->
        val nm = ReverieCoreBridge.brushPresetName(i)
        val id = BrushStoreVm.idForScanned(nm)
        BrushPresetInfo(
            index = i,
            id = id,
            name = nm,
            thumbBytes = ReverieCoreBridge.brushPresetThumbData(i) ?: ByteArray(0),
            group = userBrushGroups[id] ?: inferBrushGroup(nm),
            isBuiltIn = builtInNames.contains(nm),
        )
    }

// ---- User-defined brush groups ----------------------------------

/**
 * Import documents (revp, kra, psd, images) and convert them to .revp projects.
 */
fun PaintViewModel.importDocuments(
    uris: List<android.net.Uri>,
    context: android.content.Context,
) {
    if (uris.isEmpty()) return
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_importing_progress)

    viewModelScope.launch(Dispatchers.IO) {
        val destDir = currentFolder?.let { File(it.filePath) } ?: projectDir()
        var successCount = 0
        var lastImportedName = ""

        for ((index, uri) in uris.withIndex()) {
            if (uris.size > 1) {
                withContext(Dispatchers.Main) {
                    blockingLoadingMessage = "${getString(R.string.project_importing_progress)} (${index + 1}/${uris.size})"
                }
            }
            try {
                val defaultName = getString(R.string.project_default_import_name)
                val originalName = queryFileName(context, uri) ?: "${defaultName}_${System.currentTimeMillis() % 10000}"
                val ext = originalName.substringAfterLast('.', "").lowercase()
                val baseName = originalName.substringBeforeLast('.', originalName)

                val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}_${originalName}")
                openStreamSafely(context, uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    continue
                }

                // Deduplicate project name in destDir
                var candidateName = baseName.ifBlank { defaultName }
                var targetFile = File(destDir, "$candidateName.revp")
                var counter = 1
                while (targetFile.exists()) {
                    candidateName = "$baseName ($counter)"
                    targetFile = File(destDir, "$candidateName.revp")
                    counter++
                }

                val resolvedExt = if (ext in setOf("revp", "kra", "psd", "png", "jpg", "jpeg", "webp", "bmp")) {
                    ext
                } else {
                    sniffFileFormat(tempFile) ?: ext
                }

                val success = when (resolvedExt) {
                    "revp" -> {
                        tempFile.copyTo(targetFile, overwrite = true)
                        targetFile.exists() && targetFile.length() > 0
                    }
                    "kra" -> {
                        convertViaCore(tempFile, targetFile, candidateName, format = "kra")
                    }
                    "psd" -> {
                        convertViaCore(tempFile, targetFile, candidateName, format = "psd")
                    }
                    "png" -> {
                        convertViaCore(tempFile, targetFile, candidateName, format = "png")
                    }
                    "jpg", "jpeg", "webp", "bmp" -> {
                        val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                        if (bmp != null) {
                            val pngTemp = File(context.cacheDir, "img_conv_${System.currentTimeMillis()}.png")
                            try {
                                pngTemp.outputStream().use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                bmp.recycle()
                                convertViaCore(pngTemp, targetFile, candidateName, format = "png")
                            } finally {
                                pngTemp.delete()
                            }
                        } else {
                            false
                        }
                    }
                    else -> {
                        val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                        if (bmp != null) {
                            val pngTemp = File(context.cacheDir, "img_conv_${System.currentTimeMillis()}.png")
                            try {
                                pngTemp.outputStream().use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                bmp.recycle()
                                convertViaCore(pngTemp, targetFile, candidateName, format = "png")
                            } finally {
                                pngTemp.delete()
                            }
                        } else {
                            false
                        }
                    }
                }

                tempFile.delete()

                if (success) {
                    successCount++
                    lastImportedName = candidateName
                }
            } catch (e: Exception) {
                android.util.Log.e("RP_IMPORT", "Failed to import uri: $uri", e)
            }
        }

        withContext(Dispatchers.Main) {
            isBlockingLoading = false
            refreshProjects()
            if (successCount > 0) {
                val msg = if (successCount == 1) {
                    getString(R.string.toast_project_import_single_success, lastImportedName)
                } else {
                    getString(R.string.toast_project_import_multiple_success, successCount)
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, getString(R.string.toast_project_import_unsupported), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun PaintViewModel.convertViaCore(
    srcFile: File,
    destFile: File,
    name: String,
    format: String,
): Boolean = suspendCancellableCoroutine { cont ->
    runCore(
        render = false,
        after = {
            cont.resume(destFile.exists() && destFile.length() > 0)
        },
    ) {
        try {
            val loaded = when (format) {
                "psd" -> ReverieCoreBridge.loadPsd(srcFile.absolutePath)
                "kra" -> ReverieCoreBridge.loadRevp(srcFile.absolutePath)
                else -> ReverieCoreBridge.loadPng(srcFile.absolutePath)
            }
            if (loaded) {
                ReverieCoreBridge.setUndoLimit(maxUndoSteps)
                val extraJson = """
                {
                    "strokeCount": 0,
                    "elapsedSeconds": 0,
                    "createdTime": ${System.currentTimeMillis()},
                    "colorMode": "RGB 8位",
                    "layerCount": ${ReverieCoreBridge.layerCount()}
                }
                """.trimIndent()
                ReverieCoreBridge.saveRevp(destFile.absolutePath, extraJson, null)
            }
        } finally {
            // 导入转换完成后立即释放 native 文档, 避免多图层超大工程驻留在 g_core 累积内存峰值
            ReverieCoreBridge.closeDocument()
            releaseReclaimableCaches(aggressive = true)
            System.gc()
        }
    }
}

/**
 * 根据文件头特征字与结构探测文件真实格式，防止系统 SAF 文件选择器丢扩展名时误判。
 */
internal fun sniffFileFormat(file: File): String? {
    if (!file.exists() || file.length() < 4L) return null
    try {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read >= 4) {
                // PSD: "8BPS" (0x38, 0x42, 0x50, 0x53)
                if (header[0] == 0x38.toByte() && header[1] == 0x42.toByte() &&
                    header[2] == 0x50.toByte() && header[3] == 0x53.toByte()
                ) {
                    return "psd"
                }
                // PNG: 0x89 'P' 'N' 'G'
                if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
                ) {
                    return "png"
                }
                // JPEG: 0xFF 0xD8 0xFF
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                    return "jpg"
                }
                // ZIP: 'P' 'K' 0x03 0x04
                if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
                ) {
                    try {
                        java.util.zip.ZipFile(file).use { zip ->
                            if (zip.getEntry("meta.json") != null || zip.getEntry("layers.xml") != null) {
                                return "revp"
                            }
                            if (zip.getEntry("maindoc.xml") != null || zip.getEntry("mimetype") != null) {
                                return "kra"
                            }
                        }
                    } catch (_: Exception) {}
                    return "revp"
                }
            }
        }
    } catch (_: Exception) {}
    return null
}

internal fun openStreamSafely(context: android.content.Context, uri: android.net.Uri): java.io.InputStream? {
    return if (uri.scheme == "http" || uri.scheme == "https") {
        val conn = java.net.URL(uri.toString()).openConnection()
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.getInputStream()
    } else {
        context.contentResolver.openInputStream(uri)
    }
}

internal fun queryFileName(context: android.content.Context, uri: android.net.Uri): String? {
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        return cursor.getString(idx)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RP_IMPORT", "queryFileName failed", e)
        }
    } else if (uri.scheme == "http" || uri.scheme == "https") {
        val raw = uri.path?.substringAfterLast('/')?.substringBefore('?')
        if (!raw.isNullOrBlank()) {
            return raw
        }
        return "web_image_${System.currentTimeMillis() % 10000}.jpg"
    }
    return uri.path?.substringAfterLast('/')
}

fun isBrushUri(context: android.content.Context, uri: android.net.Uri): Boolean {
    val name = queryFileName(context, uri) ?: uri.path ?: ""
    val ext = name.substringAfterLast('.', "").substringBefore('?').lowercase()
    if (ext in com.reverie.paint.core.brushstore.BrushImportPipeline.PRESET_EXTS || ext in listOf("bundle", "abr", "gbr", "gih")) return true
    if (ext == "zip") {
        try {
            openStreamSafely(context, uri)?.use { inStream ->
                java.util.zip.ZipInputStream(inStream).use { zipIn ->
                    var count = 0
                    while (count < 100) {
                        val entry = zipIn.nextEntry ?: break
                        val eName = entry.name
                        if (eName.contains("paintoppresets/") ||
                            eName.contains("brushes/") ||
                            com.reverie.paint.core.brushstore.BrushImportPipeline.isPreset(eName) ||
                            eName.endsWith(".gbr", ignoreCase = true) ||
                            eName.endsWith(".abr", ignoreCase = true) ||
                            eName.endsWith(".bundle", ignoreCase = true) ||
                            eName == "META-INF/manifest.xml"
                        ) {
                            return true
                        }
                        count++
                    }
                }
            }
        } catch (_: Exception) {}
    }
    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
    if (mime != null && (mime.contains("kpp") || mime.contains("myb") || mime.contains("bundle") || mime.contains("photoshop-brush") || mime.contains("paintoppreset"))) {
        return true
    }
    return false
}

fun isImageFile(context: android.content.Context, uri: android.net.Uri): Boolean {
    val name = queryFileName(context, uri) ?: uri.path ?: ""
    val ext = name.substringAfterLast('.', "").substringBefore('?').lowercase()
    if (ext in listOf("png", "jpg", "jpeg", "webp", "bmp", "gif")) return true
    if (uri.scheme == "http" || uri.scheme == "https") {
        if (uri.toString().contains("image", ignoreCase = true)) return true
    }
    val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
    return mime?.startsWith("image/") == true
}

fun PaintViewModel.importImageUriToNewLayer(
    uri: android.net.Uri,
    context: android.content.Context,
) {
    val defaultImportName = getString(R.string.project_default_import_image_name)
    val fullName = queryFileName(context, uri) ?: defaultImportName
    val layerName = fullName.substringBeforeLast('.', fullName).take(30).ifBlank { defaultImportName }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStreamSafely(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSample = 1
            if (options.outWidth > 0 && options.outHeight > 0) {
                val maxDim = maxOf(options.outWidth, options.outHeight)
                val targetMax = maxOf(coreW, coreH, 2048) * 2
                while (maxDim / inSample > targetMax && inSample < 16) {
                    inSample *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSample
            }
            val bmp = openStreamSafely(context, uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
            if (bmp != null) {
                withContext(Dispatchers.Main) {
                    importImageToNewLayer(bmp, layerName = layerName) {
                        showActionToast(R.string.toast_project_inserted_layer, R.drawable.ic_check, layerName)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, getString(R.string.toast_project_decode_image_failed), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RP_IMPORT", "importImageUriToNewLayer failed", e)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, getString(R.string.toast_project_import_image_failed, e.localizedMessage ?: ""), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

fun PaintViewModel.handleIncomingUris(
    uris: List<android.net.Uri>,
    context: android.content.Context,
) {
    if (uris.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val brushUris = mutableListOf<android.net.Uri>()
        val otherUris = mutableListOf<android.net.Uri>()
        for (u in uris) {
            if (isBrushUri(context, u)) {
                brushUris.add(u)
            } else {
                otherUris.add(u)
            }
        }

        withContext(Dispatchers.Main) {
            if (brushUris.isNotEmpty()) {
                pendingExternalBrushUris = brushUris
            }
            if (otherUris.isNotEmpty()) {
                if (currentPage == Page.PAINTING) {
                    if (otherUris.size == 1 && isImageFile(context, otherUris[0])) {
                        pendingExternalImageUri = otherUris[0]
                    } else {
                        importDocuments(otherUris, context)
                        showActionToast(R.string.toast_project_imported_to_gallery, R.drawable.ic_import)
                    }
                } else {
                    importDocuments(otherUris, context)
                }
            }
        }
    }
}


