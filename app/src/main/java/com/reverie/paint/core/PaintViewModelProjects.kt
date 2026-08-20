package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

internal fun PaintViewModel.projectDir(): java.io.File {
    val extDir = appContext.getExternalFilesDir("projects")
    if (extDir != null) {
        if (!extDir.exists()) extDir.mkdirs()
        return extDir
    }
    val intDir = java.io.File(appContext.filesDir, "projects")
    if (!intDir.exists()) intDir.mkdirs()
    return intDir
}

// Blocking loading overlay state (used during canvas loading, saving, creating)

internal fun PaintViewModel.saveProject(
    name: String,
    onComplete: (() -> Unit)? = null,
) {
    tickPaintingTimer()
    isBlockingLoading = true
    blockingLoadingMessage = "正在保存作品..."
    runCore(
        after = {
            initialStrokeCount = totalStrokes
            isModified = false
            docName = name
            refreshProjects()
            isBlockingLoading = false
            onComplete?.invoke()
        },
    ) {
        val fileToSave =
            currentProjectFile?.let { File(it) }?.takeIf { it.parentFile?.exists() == true }
                ?: File(projectDir(), "$name.revp")

        // If the name changed and we had a path, adjust destination file
        val finalFile =
            if (fileToSave.nameWithoutExtension != name) {
                File(fileToSave.parentFile, "$name.revp")
            } else {
                fileToSave
            }
        currentProjectFile = finalFile.absolutePath

        val extraJson =
            """
            {
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size}
            }
            """.trimIndent()
        // Recording blob goes straight into the .revp via the C++ store
        // (single write, no post-save ZIP repackage)
        val recBlob = recorder.serialize()
        android.util.Log.d("ReverieRec", "saveRevp blob=${recBlob?.size ?: 0} bytes")
        val saved = ReverieCoreBridge.saveRevp(finalFile.absolutePath, extraJson, recBlob)
        if (!saved && !(finalFile.exists() && finalFile.length() > 0)) {
            android.util.Log.w("ReverieRec", "saveRevp failed, no artifact")
        }
    }
}

/** Append the session recording as a "recording" entry inside the .revp
 *  ZIP container. The file is repackaged entry-by-entry (streamed, no
 *  full-file RAM buffering) and atomically swapped back in place. */

/** Recording blob is now written directly by the C++ store during
 *  saveRevp (single write, no post-save ZIP repackage). */
private fun PaintViewModel.recSessionDir(): File = File(appContext.filesDir, "rec_session")

internal fun PaintViewModel.loadProject(p: com.reverie.paint.model.Project) {
    stopPaintingTimer()
    // Navigate to painting page first, then show loading overlay while reading native file
    currentPage = Page.PAINTING
    isBlockingLoading = true
    blockingLoadingMessage = "正在载入画布..."
    runCore(
        after = {
            initialStrokeCount = p.strokeCount
            totalStrokes = p.strokeCount
            isModified = false
            docWidth = coreW
            docHeight = coreH
            docName = p.name
            currentProjectFile = p.filePath
            elapsedSeconds = p.elapsedSeconds
            canvasCreatedTime = if (p.lastModified > 0) p.lastModified else System.currentTimeMillis()
            colorMode = p.colorMode
            isBlockingLoading = false
            startPaintingTimer()
        },
    ) {
        val file = java.io.File(p.filePath)
        if (file.exists()) {
            val ok =
                if (file.extension.equals("revp", ignoreCase = true) || file.extension.equals("kra", ignoreCase = true)) {
                    ReverieCoreBridge.loadRevp(file.absolutePath)
                } else {
                    ReverieCoreBridge.loadPng(file.absolutePath)
                }
            if (ok) {
                coreW = ReverieCoreBridge.docWidth()
                coreH = ReverieCoreBridge.docHeight()
                renderW = coreW
                renderH = coreH
                displayBufferInvalid = true
                syncLayersFromNative()
                ReverieCoreBridge.setBrushColor(brushColor)
                recorder.beginSession(coreW, coreH, file, recSessionDir())
            }
        }
    }
}

// Current folder stack navigation: null means Root, otherwise the folder Project

internal fun PaintViewModel.createFolder(name: String) {
    val root = projectDir()
    val folder = File(root, name.trim())
    if (!folder.exists()) {
        folder.mkdirs()
    }
    refreshProjects()
}

internal fun PaintViewModel.moveProjectToFolder(
    p: com.reverie.paint.model.Project,
    targetFolderName: String?,
) {
    val srcFile = File(p.filePath)
    if (!srcFile.exists()) return
    val root = projectDir()
    val destDir = if (targetFolderName.isNullOrBlank()) root else File(root, targetFolderName)
    if (!destDir.exists()) destDir.mkdirs()
    val destFile = File(destDir, srcFile.name)
    srcFile.renameTo(destFile)
    refreshProjects()
}

internal fun PaintViewModel.deleteProject(p: com.reverie.paint.model.Project) {
    if (p.isFolder) {
        val dir = File(p.filePath)
        if (dir.exists() && dir.isDirectory) {
            dir.deleteRecursively()
        }
    } else {
        val file = File(p.filePath)
        if (file.exists()) file.delete()
    }
    refreshProjects()
}

internal fun PaintViewModel.renameProject(
    p: com.reverie.paint.model.Project,
    newName: String,
) {
    val file = File(p.filePath)
    if (file.exists()) {
        val target =
            if (p.isFolder) {
                File(file.parentFile, newName.trim())
            } else {
                File(file.parentFile, "${newName.trim()}.${file.extension}")
            }
        file.renameTo(target)
    }
    refreshProjects()
}

internal fun PaintViewModel.parseProjectFromFile(f: File): com.reverie.paint.model.Project {
    // Metadata cache: parsing re-opens the ZIP per file per refresh, which
    // adds up quickly with many projects on the home page. The entry is
    // valid while the file's mtime and size are unchanged.
    val cached = projectMetaCache[f.absolutePath]
    if (cached != null && cached.mtime == f.lastModified() && cached.size == f.length()) {
        return cached.project
    }
    var w = 1080
    var h = 1920
    var strokes = 0
    var elapsed = 0L
    var layerCount = 1
    var colorModeStr = "RGB 8位"
    var previewPath = ""
    var hasRec = false

    val ext = f.extension.lowercase()
    if (ext == "revp" || ext == "kra") {
        try {
            val zip = ZipFile(f)
            try {
                val metaEntry = zip.getEntry("meta.json")
                if (metaEntry != null) {
                    val stream = zip.getInputStream(metaEntry)
                    val text = stream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    w = json.optInt("width", 1080)
                    h = json.optInt("height", 1920)
                    strokes = json.optInt("strokeCount", 0)
                    elapsed = json.optLong("elapsedSeconds", 0L)
                    colorModeStr = json.optString("colorMode", "RGB 8位")
                    layerCount = json.optJSONArray("layers")?.length() ?: 1
                }
                val prevEntry = zip.getEntry("thumbnail.png") ?: zip.getEntry("preview.png")
                if (prevEntry != null) {
                    val cacheThumb = File(appContext.cacheDir, "${f.nameWithoutExtension}_thumb.png")
                    if (!cacheThumb.exists() || cacheThumb.lastModified() < f.lastModified()) {
                        zip.getInputStream(prevEntry).use { input ->
                            cacheThumb.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    previewPath = cacheThumb.absolutePath
                }
                hasRec = zip.getEntry("recording") != null
            } finally {
                zip.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("ReveriePaint", "Failed to parse revp metadata: ${f.name}", e)
        }
    } else if (ext == "png") {
        previewPath = f.absolutePath
    }

    return com.reverie.paint.model
        .Project(
            name = f.nameWithoutExtension,
            width = w,
            height = h,
            filePath = f.absolutePath,
            previewPath = previewPath,
            strokeCount = strokes,
            elapsedSeconds = elapsed,
            lastModified = f.lastModified(),
            layerCount = layerCount,
            colorMode = colorModeStr,
            fileSize = f.length(),
            isFolder = false,
            hasRecording = hasRec,
        ).also { p ->
            projectMetaCache[f.absolutePath] = ProjectMetaCacheEntry(f.lastModified(), f.length(), p)
        }
}

private class ProjectMetaCacheEntry(
    val mtime: Long,
    val size: Long,
    val project: com.reverie.paint.model.Project,
)

private val projectMetaCache = HashMap<String, ProjectMetaCacheEntry>()

internal fun PaintViewModel.refreshProjects() {
    val rootDir = projectDir()
    if (!rootDir.exists()) {
        projects = emptyList()
        return
    }

    // If currently inside a folder, read projects inside that subfolder
    val folder = currentFolder
    if (folder != null && folder.isFolder) {
        val dir = File(folder.filePath)
        if (!dir.exists()) {
            currentFolder = null
            refreshProjects()
            return
        }
        val files: Array<File> =
            dir
                .listFiles { f: File -> f.isFile && f.extension.lowercase() in listOf("revp", "kra", "png") }
                ?.sortedByDescending { it.lastModified() }
                ?.toTypedArray() ?: emptyArray()

        val list = files.map { parseProjectFromFile(it) }
        projects = list
        return
    }

    // Root level: read both standalone files and folders (画集)
    val list = mutableListOf<com.reverie.paint.model.Project>()
    val allEntries: Array<File> =
        rootDir
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.toTypedArray() ?: emptyArray()

    for (entry in allEntries) {
        if (entry.isDirectory) {
            // Folder / Stack (画集)
            val subFiles: Array<File> =
                entry
                    .listFiles { f: File -> f.isFile && f.extension.lowercase() in listOf("revp", "kra", "png") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.toTypedArray() ?: emptyArray()

            val subProjects = subFiles.map { parseProjectFromFile(it) }
            val topThumb = subProjects.firstOrNull()?.previewPath ?: ""
            val topModified = maxOf(entry.lastModified(), subProjects.maxOfOrNull { it.lastModified } ?: 0L)

            list.add(
                com.reverie.paint.model.Project(
                    name = entry.name,
                    filePath = entry.absolutePath,
                    previewPath = topThumb,
                    lastModified = topModified,
                    isFolder = true,
                    folderPath = entry.absolutePath,
                    items = subProjects,
                ),
            )
        } else if (entry.isFile && entry.extension.lowercase() in listOf("revp", "kra", "png")) {
            list.add(parseProjectFromFile(entry))
        }
    }
    projects = list
}

/**
 * Export the current artwork into various formats:
 * PNG, JPG, PSD, TIFF, KRA, REVP
 */
internal fun PaintViewModel.exportDocument(
    format: String,
    targetFile: java.io.File,
    onSuccess: (java.io.File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val fmt = format.lowercase()
    runCore(render = false) {
        val ok =
            when (fmt) {
                "png" -> {
                    ReverieCoreBridge.savePng(targetFile.absolutePath)
                }

                "jpg", "jpeg" -> {
                    ReverieCoreBridge.exportJpg(targetFile.absolutePath, 95)
                }

                "psd" -> {
                    ReverieCoreBridge.exportPsd(targetFile.absolutePath)
                }

                "kra" -> {
                    ReverieCoreBridge.saveKra(targetFile.absolutePath)
                }

                "revp" -> {
                    val extraJson =
                        """
                        {
                            "strokeCount": $totalStrokes,
                            "elapsedSeconds": $elapsedSeconds,
                            "createdTime": $canvasCreatedTime,
                            "colorMode": "$colorMode",
                            "layerCount": ${layers.size}
                        }
                        """.trimIndent()
                    ReverieCoreBridge.saveRevp(targetFile.absolutePath, extraJson, recorder.serialize())
                }

                "tiff", "tif" -> {
                    // Export TIFF via bitmap compression or lossless PNG container fallback
                    val tempPng = java.io.File(appContext.cacheDir, "temp_tiff.png")
                    if (ReverieCoreBridge.savePng(tempPng.absolutePath)) {
                        val bmp = android.graphics.BitmapFactory.decodeFile(tempPng.absolutePath)
                        if (bmp != null) {
                            targetFile.outputStream().use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }

                else -> {
                    ReverieCoreBridge.savePng(targetFile.absolutePath)
                }
            }
        mainHandler.post {
            if (ok && targetFile.exists() && targetFile.length() > 0) {
                onSuccess(targetFile)
            } else {
                onError("导出 $format 失败")
            }
        }
    }
}

internal fun PaintViewModel.goHome() {
    recorder.endSession()
    stopPaintingTimer()
    currentPage = Page.HOME
}

internal fun PaintViewModel.goCreate() {
    stopPaintingTimer()
    currentPage = Page.CREATE
}

internal fun PaintViewModel.generateNextProjectName(): String {
    val root = projectDir()
    val existingFiles = (root.listFiles() ?: emptyArray()).map { it.nameWithoutExtension.lowercase() }.toSet()
    var index = 1
    var candidate = "未命名作品"
    if (!existingFiles.contains(candidate.lowercase())) {
        return candidate
    }
    while (existingFiles.contains("未命名作品 $index".lowercase())) {
        index++
    }
    return "未命名作品 $index"
}

internal fun PaintViewModel.startPainting(
    w: Int,
    h: Int,
    name: String? = null,
) {
    val actualName = name?.ifBlank { null } ?: generateNextProjectName()
    currentProjectFile = null // Reset so new artwork won't overwrite previous project file
    totalStrokes = 0
    elapsedSeconds = 0L
    canvasCreatedTime = System.currentTimeMillis()
    stopPaintingTimer()
    currentPage = Page.PAINTING
    isBlockingLoading = true
    blockingLoadingMessage = "正在创建画布..."
    runCore(
        after = {
            initialStrokeCount = 0
            totalStrokes = 0
            isModified = false
            docWidth = w
            docHeight = h
            docName = actualName
            isBlockingLoading = false
            startPaintingTimer()
        },
    ) {
        if (ReverieCoreBridge.newDocument(w, h)) {
            coreW = w
            coreH = h
            renderW = w
            renderH = h
            displayBufferInvalid = true
            syncLayersFromNative()
            ReverieCoreBridge.setBrushColor(brushColor)
            recorder.beginSession(w, h, snapshotSource = null, snapshotTempDir = recSessionDir())
        }
    }
}

/** Enter the replay page for a project that embeds a recording. */
internal fun PaintViewModel.goReplay(p: com.reverie.paint.model.Project) {
    recorder.endSession()
    stopPaintingTimer()
    currentPage = Page.REPLAY
    isBlockingLoading = true
    blockingLoadingMessage = "正在准备回放..."
    var session: ReplaySession? = null
    runCore(
        after = {
            isBlockingLoading = false
            if (session == null) {
                showActionToast("此作品没有回放数据", R.drawable.ic_clock)
                goHome()
                return@runCore
            }
            val s = session!!
            replaySession = s
            docWidth = s.docW
            docHeight = s.docH
            s.currentMs = 0
            s.progress = 0f
            s.elapsedMs = 0L
            s.lastProgressWallMs = android.os.SystemClock.elapsedRealtime()
            s.isPlaying = true
            scheduleReplayStep(s)
        },
    ) {
        session = ReplaySession.load(java.io.File(p.filePath), File(appContext.filesDir, "replay_tmp"))
        if (session != null) {
            resetReplayDocLocked(session!!)
            // Replay applies strokes/ops directly - no undo history growth
            ReverieCoreBridge.setUndoCaptureEnabled(false)
        }
    }
}

internal fun PaintViewModel.openProject(p: com.reverie.paint.model.Project) {
    loadProject(p)
}

internal fun PaintViewModel.refreshDisplay() {
    scheduleRender(immediate = true)
}

internal fun PaintViewModel.loadBrushPresets() {
    loadToolOptions()
    loadBrushParams()
    // Copy the bundled presets from assets to filesDir once
    val dir = java.io.File(appContext.filesDir, "paintoppresets")
    val assets = appContext.assets
    try {
        if (!dir.exists()) dir.mkdirs()
        for (name in assets.list("paintoppresets") ?: emptyArray()) {
            val target = java.io.File(dir, name)
            if (!target.exists()) {
                assets.open("paintoppresets/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ReveriePaint", "preset copy failed", e)
    }
    android.util.Log.d("ReveriePaint", "loadBrushPresets files=" + (dir.list()?.size ?: -1))
    // Copy the bundled brush resource files (.gbr/.gih/.png/.svg) from
    // assets to filesDir once, so presets can resolve their
    // brush_definition files via the shared KisLocalStrokeResources.
    val brushDir = java.io.File(appContext.filesDir, "brushes")
    try {
        if (!brushDir.exists()) brushDir.mkdirs()
        for (name in assets.list("brushes") ?: emptyArray()) {
            val target = java.io.File(brushDir, name)
            if (!target.exists()) {
                assets.open("brushes/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ReveriePaint", "brush copy failed", e)
    }
    // Restore persisted user brush groups and custom order
    loadBrushGroups()
    loadCategoryOrder()
    val orderJson = prefs().getString("brush_order", null)
    brushOrder =
        if (orderJson != null) {
            runCatching {
                val arr = org.json.JSONArray(orderJson)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    // Build the list on the render thread (JNI reads), but assign the
    // Compose state on the MAIN thread: mutableStateOf written from the
    // render HandlerThread is not reliably visible to composition.
    val list = ArrayList<BrushPresetInfo>()
    runCore(after = {
        android.util.Log.d("ReveriePaint", "loadBrushPresets assign=${list.size}")
        brushPresets = list.toList()
        if (brushPresets.isNotEmpty()) {
            val savedToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
            val savedToolState = toolBrushStates[savedToolId]
            val targetIndex =
                if (savedToolState != null && savedToolState.presetIndex in brushPresets.indices) {
                    savedToolState.presetIndex
                } else {
                    val savedPresetIdx = prefs().getInt("last_brush_preset_index", 0)
                    if (savedPresetIdx in brushPresets.indices) savedPresetIdx else 0
                }
            applyTool(savedToolId)
            selectBrushPreset(targetIndex)
        }
    }) {
        android.util.Log.d("ReveriePaint", "loadBrushPresets runCore start")
        val nrb = ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
        android.util.Log.d("ReveriePaint", "loadBrushResources count=$nrb")
        val n = ReverieCoreBridge.loadBrushPresetsFromDir(dir.absolutePath)
        android.util.Log.d("ReveriePaint", "loadBrushPresets count=$n")
        val builtInNames = appContext.assets.list("paintoppresets")?.map { it.removeSuffix(".kpp") }?.toSet() ?: emptySet()
        list.clear()
        for (i in 0 until n) {
            val nm = ReverieCoreBridge.brushPresetName(i)
            list.add(
                BrushPresetInfo(
                    index = i,
                    name = nm,
                    thumbBytes = ReverieCoreBridge.brushPresetThumbData(i),
                    group = userBrushGroups[nm] ?: inferBrushGroup(nm),
                    isBuiltIn = builtInNames.contains(nm),
                ),
            )
        }
        android.util.Log.d("ReveriePaint", "loadBrushPresets list=${list.size}")
    }
}

// ---- User-defined brush groups ----------------------------------
