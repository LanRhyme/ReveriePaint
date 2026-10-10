/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import com.reverie.paint.model.AutoSaveSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.util.zip.ZipFile

internal fun PaintViewModel.projectDir(): java.io.File {
    val intDir = java.io.File(appContext.filesDir, "projects")
    if (!intDir.exists()) intDir.mkdirs()
    return intDir
}

internal fun PaintViewModel.autoSaveDir(): File {
    val dir = File(appContext.filesDir, "autosave")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

internal fun estimateProjectSaveBytes(w: Int, h: Int, layerCount: Int, currentFileSize: Long): Long {
    val minBuffer = 50L * 1024 * 1024 // 50MB 安全保留余量
    return if (currentFileSize > 0L) {
        currentFileSize * 2 + minBuffer
    } else {
        val rawBytes = w.toLong() * h * 4 * layerCount.coerceAtLeast(1)
        (rawBytes * 35 / 100) + minBuffer
    }
}

internal fun checkProjectDiskSpace(targetFile: File, requiredBytes: Long): Boolean {
    val dir = targetFile.parentFile?.takeIf { it.exists() } ?: targetFile
    return try {
        val stat = android.os.StatFs(dir.absolutePath)
        stat.availableBytes >= requiredBytes
    } catch (_: Throwable) {
        true
    }
}

// Blocking loading overlay state (used during canvas loading, saving, creating)

/** Calls [onComplete] only after a successful save; failures keep the current document open and dirty state intact. */
internal fun PaintViewModel.saveProject(
    name: String,
    onComplete: (() -> Unit)? = null,
) {
    val fileToSave =
        currentProjectFile?.let { File(it) }?.takeIf { it.parentFile?.exists() == true && !it.name.contains(".autosave") }
            ?: File(projectDir(), "$name.revp")
    val finalFile =
        if (fileToSave.nameWithoutExtension != name) {
            File(fileToSave.parentFile, "$name.revp")
        } else {
            fileToSave
        }

    val requiredSpace = estimateProjectSaveBytes(
        docWidth,
        docHeight,
        layers.size,
        if (finalFile.exists()) finalFile.length() else 0L,
    )
    if (!checkProjectDiskSpace(finalFile, requiredSpace)) {
        lowStorageMessage = getString(R.string.dialog_storage_insufficient_desc)
        showLowStorageDialog = true
        showActionToast(R.string.toast_project_save_low_storage, R.drawable.ic_alert_triangle)
        return
    }

    val referenceProfile = referenceProfileId
    val previousReferencePath = currentProjectFile
    tickPaintingTimer()
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_saving_progress)
    var savedFile: File? = null
    runCore(
        after = {
            isBlockingLoading = false
            val targetFile = savedFile
            if (targetFile == null) {
                Breadcrumbs.record("Save", "saveProject FAILED for: $name")
                showActionToast(R.string.toast_project_save_failed, R.drawable.ic_save)
                return@runCore
            }
            Breadcrumbs.record("Save", "saveProject SUCCESS: $name ($totalStrokes strokes)")
            currentProjectFile = targetFile.absolutePath
            // Renaming from the save dialog moves this working association; do not let the
            // previous file and the renamed artwork share a mutable reference selection.
            previousReferencePath?.takeIf { it != targetFile.absolutePath }?.let(referenceStore::forgetPath)
            referenceStore.bind(targetFile.absolutePath, referenceProfile)
            initialStrokeCount = totalStrokes
            isModified = false
            docName = name

            // 显式保存成功后，才清理对应的自动保存草稿
            val autoSaveFile = File(autoSaveDir(), "$name.autosave.revp")
            if (autoSaveFile.exists()) {
                autoSaveFile.delete()
            }
            referenceStore.forgetPath(autoSaveFile.absolutePath)
            val autoSaveTmp = File(autoSaveDir(), "$name.autosave.revp.tmp")
            if (autoSaveTmp.exists()) {
                autoSaveTmp.delete()
            }
            refreshProjects()
            maybeAutoBackup()
            onComplete?.invoke()
        },
    ) {
        val fileToSave =
            currentProjectFile?.let { File(it) }?.takeIf { it.parentFile?.exists() == true && !it.name.contains(".autosave") }
                ?: File(projectDir(), "$name.revp")

        // If the name changed and we had a path, adjust destination file
        val finalFile =
            if (fileToSave.nameWithoutExtension != name) {
                File(fileToSave.parentFile, "$name.revp")
            } else {
                fileToSave
            }

        val extraJson =
            """
            {
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size},
                "dpi": $docDpi,
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex,
                "collapsedGroups": [${collapsedGroupNames.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }}]
            }
            """.trimIndent()
        // Recording blob goes straight into the .revp via the C++ store
        // (single write, no post-save ZIP repackage)
        val recBlob = recorder.serialize()
        android.util.Log.d("RP_IO", "saveRevp blob=${recBlob?.size ?: 0} bytes to ${finalFile.absolutePath}")
        val saved = ReverieCoreBridge.saveRevp(finalFile.absolutePath, extraJson, recBlob)
        android.util.Log.d("RP_IO", "saveRevp result=$saved, file exists=${finalFile.exists()}, length=${finalFile.length()}")
        // An old non-empty file is not evidence that this save succeeded.
        // Leave the result unset on false or exception so runCore's after callback cannot report success.
        if (saved && finalFile.isFile && finalFile.length() > 0) {
            savedFile = finalFile
        } else {
            android.util.Log.w("RP_IO", "saveRevp failed, retaining document state and autosave draft")
        }
    }
}

internal fun PaintViewModel.autoSaveProject(isPeriodic: Boolean = true) {
    if (isAutoSaving || isBlockingLoading) return
    val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
    if (touchView?.isInteracting == true || touchView?.isTransformActive == true) return

    val name = docName.ifBlank { if (LanguageManager.isChinese()) "未命名作品" else "Untitled Artwork" }
    val autoSaveFile = File(autoSaveDir(), "$name.autosave.revp")
    val requiredSpace = estimateProjectSaveBytes(
        docWidth,
        docHeight,
        layers.size,
        if (autoSaveFile.exists()) autoSaveFile.length() else 0L,
    )
    if (!checkProjectDiskSpace(autoSaveFile, requiredSpace)) {
        android.util.Log.w("RP_IO", "autoSaveProject: insufficient disk space ($requiredSpace bytes required), suspending autosave")
        return
    }

    isAutoSaving = true
    tickPaintingTimer()
    val strokeCount = totalStrokes
    val layerCount = layers.size
    val currentMasterPath = currentProjectFile?.takeIf { !it.contains(".autosave") && !it.contains(".emergency") } ?: ""
    val referenceProfile = referenceProfileId

    runCore(
        render = false,
        after = {
            lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
            strokesSinceLastAutoSave = 0
            hasPendingMajorOp = false
            isAutoSaving = false
            if (isPeriodic && autoSaveToastEnabled) {
                showActionToast(R.string.toast_project_autosaved, R.drawable.ic_save)
            }
        },
    ) {
        val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        if (tv?.isInteracting == true || tv?.isTransformActive == true) {
            return@runCore
        }

        val extraJson =
            """
            {
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size},
                "isAutoSave": true,
                "masterFilePath": "$currentMasterPath",
                "docName": "$name",
                "dpi": $docDpi,
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex,
                "collapsedGroups": [${collapsedGroupNames.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }}]
            }
            """.trimIndent()
        val recBlob = recorder.serialize()
        android.util.Log.d("RP_IO", "autoSaveRevpAsync blob=${recBlob?.size ?: 0} bytes to ${autoSaveFile.absolutePath}")
        val saveStartTime = System.currentTimeMillis()
        val saved = ReverieCoreBridge.saveRevpAsync(autoSaveFile.absolutePath, extraJson, recBlob)
        android.util.Log.d("RP_IO", "autoSaveRevpAsync triggered=$saved")

        if (saved) {
            viewModelScope.launch(Dispatchers.IO) {
                var waitMs = 0
                while (waitMs < 60_000) {
                    delay(300)
                    waitMs += 300
                    if (autoSaveFile.exists() && autoSaveFile.lastModified() >= saveStartTime) {
                        val isValid = try {
                            ZipFile(autoSaveFile).use { it.getEntry("meta.json") != null }
                        } catch (_: Throwable) {
                            false
                        }
                        if (isValid) {
                            referenceStore.bind(autoSaveFile.absolutePath, referenceProfile)
                            AutoSaveHistoryManager.recordSnapshot(
                                context = appContext,
                                sourceRevpFile = autoSaveFile,
                                displayName = name,
                                masterPath = currentMasterPath,
                                strokeCount = strokeCount,
                                layerCount = layerCount,
                            )
                            break
                        }
                    }
                }
            }
        }
    }
}

internal fun PaintViewModel.emergencySaveOnCrash() {
    if (currentPage != Page.PAINTING) return
    try {
        val name = docName.ifBlank { if (LanguageManager.isChinese()) "未命名作品" else "Untitled Artwork" }
        val emergencyFile = File(autoSaveDir(), "$name.emergency.revp")
        val masterPath = currentProjectFile?.takeIf { !it.contains(".autosave") && !it.contains(".emergency") } ?: ""
        val extraJson =
            """
            {
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size},
                "isEmergencySave": true,
                "isAutoSave": true,
                "masterFilePath": "$masterPath",
                "docName": "$name",
                "dpi": $docDpi,
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex
            }
            """.trimIndent()
        val recBlob = try { recorder.serialize() } catch (_: Throwable) { null }
        val saved = ReverieCoreBridge.saveRevp(emergencyFile.absolutePath, extraJson, recBlob)
        if (saved && emergencyFile.exists() && emergencyFile.length() > 0) {
            referenceStore.bind(emergencyFile.absolutePath, referenceProfileId)
            AutoSaveHistoryManager.recordSnapshot(
                context = appContext,
                sourceRevpFile = emergencyFile,
                displayName = "$name (崩溃抢救)",
                masterPath = masterPath,
                strokeCount = totalStrokes,
                layerCount = layers.size,
                isEmergency = true,
            )
        }
    } catch (t: Throwable) {
        android.util.Log.e("RP_IO", "emergencySaveOnCrash failed", t)
    }
}

internal fun PaintViewModel.restoreAutoSaveSnapshot(snapshot: AutoSaveSnapshot, asCopy: Boolean = false) {
    val dir = AutoSaveHistoryManager.getHistoryDir(appContext)
    val snapFile = File(dir, snapshot.fileName)
    if (!snapFile.exists()) return

    val targetFile: File
    val projectName: String
    val cleanName = snapshot.displayName.removeSuffix(" (崩溃抢救)").removeSuffix(" (Emergency Rescue)")
    if (asCopy) {
        val rootDir = projectDir()
        var candidateName = "$cleanName (副本)"
        var candidateFile = File(rootDir, "$candidateName.revp")
        var copyIdx = 2
        while (candidateFile.exists()) {
            candidateName = "$cleanName (副本 $copyIdx)"
            candidateFile = File(rootDir, "$candidateName.revp")
            copyIdx++
        }
        projectName = candidateName
        targetFile = candidateFile
        snapFile.copyTo(targetFile, overwrite = true)
    } else {
        targetFile = if (snapshot.masterPath.isNotBlank() && File(snapshot.masterPath).parentFile?.exists() == true) {
            File(snapshot.masterPath)
        } else {
            File(projectDir(), "$cleanName.revp")
        }
        projectName = targetFile.nameWithoutExtension
        snapFile.copyTo(targetFile, overwrite = true)
    }

    val project = parseProjectFromFile(targetFile).copy(
        name = projectName,
        filePath = targetFile.absolutePath,
        isAutoSaved = false,
    )
    // Reference copies are local working aids, not historical snapshot contents.
    // A restored copy starts without references; restoring the original may reuse its local mapping.
    if (asCopy) referenceStore.forgetPath(targetFile.absolutePath)
    loadProject(project)
    showActionToast(appContext.getString(R.string.toast_project_restored_autosave), R.drawable.ic_check)
}

internal fun PaintViewModel.discardAndExit() {
    val name = docName.ifBlank { if (LanguageManager.isChinese()) "未命名作品" else "Untitled Artwork" }
    // 1. 删除本次会话产生的所有自动保存临时草稿与抢救文件
    val autoSaveFile = File(autoSaveDir(), "$name.autosave.revp")
    if (autoSaveFile.exists()) {
        autoSaveFile.delete()
    }
    forgetLocalReferences(autoSaveFile)
    val autoSaveTmp = File(autoSaveDir(), "$name.autosave.revp.tmp")
    if (autoSaveTmp.exists()) {
        autoSaveTmp.delete()
    }
    val emergencyFile = File(autoSaveDir(), "$name.emergency.revp")
    if (emergencyFile.exists()) {
        emergencyFile.delete()
    }
    forgetLocalReferences(emergencyFile)
    if (currentProjectFile != null && (currentProjectFile!!.contains(".autosave") || currentProjectFile!!.contains(".emergency"))) {
        val f = File(currentProjectFile!!)
        if (f.exists()) f.delete()
    }
    // 2. 退出到画廊主页
    goHome()
}

/** Append the session recording as a "recording" entry inside the .revp
 *  ZIP container. The file is repackaged entry-by-entry (streamed, no
 *  full-file RAM buffering) and atomically swapped back in place. */

/** Recording blob is now written directly by the C++ store during
 *  saveRevp (single write, no post-save ZIP repackage). */
private fun PaintViewModel.recSessionDir(): File = File(appContext.filesDir, "rec_session")

internal fun PaintViewModel.loadProject(p: com.reverie.paint.model.Project) {
    android.util.Log.d("RP_IO", "loadProject START: name=${p.name}, path=${p.filePath}, isAutoSaved=${p.isAutoSaved}")
    stopPaintingTimer()
    resetAnimationState()
    resetProjectReferences(loading = true)
    val referenceLoadSession = referenceSession
    // Navigate to painting page first, then show loading overlay while reading native file
    currentPage = Page.PAINTING
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_loading_progress)
    val isRecovered = p.isAutoSaved || p.filePath.contains(".autosave")
    docName = p.name
    lastAutoSaveTimeMs = 0L
    val masterFile = File(projectDir(), "${p.name}.revp")
    currentProjectFile = if (masterFile.exists() && masterFile.absolutePath != p.filePath) {
        masterFile.absolutePath
    } else if (!isRecovered) {
        p.filePath
    } else {
        null
    }

    var loadedCollapsedGroups: Set<String> = emptySet()
    var documentLoaded = false
    var isHealed = false
    runCore(
        after = {
            if (!documentLoaded) {
                Breadcrumbs.record("IO", "loadProject FAILED for: ${p.name}")
                isBlockingLoading = false
                showActionToast(R.string.toast_project_import_unsupported, R.drawable.ic_alert_triangle)
                return@runCore
            }
            Breadcrumbs.record("IO", "loadProject SUCCESS: ${p.name} (healed=$isHealed)")
            if (referenceSession == referenceLoadSession) {
                if (documentLoaded) restoreLocalProjectReferences(p.filePath)
                else referenceLoading = false
            }
            collapsedGroupNames = loadedCollapsedGroups
            initialStrokeCount = p.strokeCount
            totalStrokes = p.strokeCount
            isModified = isRecovered || isHealed // 异常恢复或自愈的工程标记为未保存
            docWidth = if (coreW > 0) coreW else p.width
            docHeight = if (coreH > 0) coreH else p.height
            docDpi = if (p.dpi > 0) p.dpi else 300
            docName = p.name
            checkBrushSizeLimit()

            val masterFile = File(projectDir(), "${p.name}.revp")
            currentProjectFile = if (masterFile.exists() && masterFile.absolutePath != p.filePath) {
                masterFile.absolutePath
            } else if (!isRecovered) {
                p.filePath
            } else {
                null
            }

            elapsedSeconds = p.elapsedSeconds
            canvasCreatedTime = if (p.lastModified > 0) p.lastModified else System.currentTimeMillis()
            colorMode = p.colorMode
            syncLayersFromNative()
            val targetLayer = p.selectedLayerIndex
            if (targetLayer in 0 until layers.size && currentLayerIndex != targetLayer) {
                currentLayerIndex = targetLayer
            }
            isBlockingLoading = false
            startPaintingTimer()
            if (isRecovered) {
                showActionToast(R.string.toast_project_restored_autosave, R.drawable.ic_save)
            } else if (isHealed) {
                showActionToast(R.string.toast_project_healed_recovery, R.drawable.ic_alert_triangle)
            }
            android.util.Log.d("RP_IO", "loadProject AFTER: docW=$docWidth, docH=$docHeight, currentProjectFile=$currentProjectFile, isModified=$isModified, layers=${layers.size}, currentLayer=$currentLayerIndex, isHealed=$isHealed")
        },
    ) {
        val file = java.io.File(p.filePath)
        android.util.Log.d("RP_IO", "loadProject OP: file exists=${file.exists()}, length=${file.length()}")
        if (file.exists()) {
            val ok =
                if (file.extension.equals("revp", ignoreCase = true) || file.extension.equals("kra", ignoreCase = true)) {
                    if (file.extension.equals("revp", ignoreCase = true)) {
                        try {
                            java.util.zip.ZipFile(file).use { zip ->
                                val metaEntry = zip.getEntry("meta.json")
                                if (metaEntry != null) {
                                    val text = zip.getInputStream(metaEntry).bufferedReader().use { it.readText() }
                                    val json = org.json.JSONObject(text)
                                    val arr = json.optJSONArray("collapsedGroups")
                                    if (arr != null) {
                                        val set = mutableSetOf<String>()
                                        for (i in 0 until arr.length()) {
                                            set.add(arr.getString(i))
                                        }
                                        loadedCollapsedGroups = set
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("RP_IO", "Failed to parse collapsedGroups", e)
                        }
                    }
                    val res = ReverieCoreBridge.loadRevp(file.absolutePath)
                    android.util.Log.d("RP_IO", "loadRevp returned $res, nativeDocW=${ReverieCoreBridge.docWidth()}, nativeDocH=${ReverieCoreBridge.docHeight()}, nativeLayers=${ReverieCoreBridge.layerCount()}")
                    res
                } else {
                    ReverieCoreBridge.loadPng(file.absolutePath)
                }
            if (ok) {
                documentLoaded = true
                isHealed = ReverieCoreBridge.isLastLoadHealed()
                coreW = ReverieCoreBridge.docWidth()
                coreH = ReverieCoreBridge.docHeight()
                renderW = coreW
                renderH = coreH
                displayBufferInvalid = true
                // 新文档的撤销栈是 C++ 侧新建的, 每次都要重新应用历史上限
                ReverieCoreBridge.setUndoLimit(maxUndoSteps)
                ReverieCoreBridge.setBrushColor(brushColor)
                ReverieCoreBridge.setBrushSecondaryColor(brushSecondaryColor)
                refreshSavedSelections()

                // 恢复退出时选中的图层
                val nLayers = ReverieCoreBridge.layerCount()
                var targetLayer = p.selectedLayerIndex
                if (targetLayer !in 0 until nLayers && file.extension.equals("revp", ignoreCase = true)) {
                    try {
                        ZipFile(file).use { zip ->
                            val metaEntry = zip.getEntry("meta.json")
                            if (metaEntry != null) {
                                val text = zip.getInputStream(metaEntry).bufferedReader().use { it.readText() }
                                val json = JSONObject(text)
                                val idx = json.optInt("selectedLayerIndex", json.optInt("activeLayerIndex", json.optInt("currentLayerIndex", -1)))
                                if (idx in 0 until nLayers) {
                                    targetLayer = idx
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (targetLayer in 0 until nLayers) {
                    ReverieCoreBridge.setCurrentLayer(targetLayer)
                }
                // Chain the project's own recording so new strokes extend the
                // existing history instead of replaying on top of a baked
                // static image (snapshot = the recording's initial document).
                val priorRec =
                    if (file.extension.equals("revp", ignoreCase = true)) {
                        PaintRecorder.readRecordingEntry(file)
                    } else {
                        null
                    }
                recorder.beginSession(coreW, coreH, file, recSessionDir(), priorRec)
                // 打开的工程可能是动画工程 (KRA/REVP 内已存有关键帧通道)。这里在
                // 渲染线程上补同步一次动画状态, 并让时间轴自动展开 —— 否则用户
                // 重新打开逐帧工程时会看不到任何帧的证据, 以为帧全丢了。
                val animated = ReverieCoreBridge.animationEnabled()
                if (animated) {
                    syncAnimationFromNative()
                }
                mainHandler.post {
                    if (animated) {
                        anim.enabled = true
                        anim.panelOpen = true
                    } else {
                        resetAnimationState()
                    }
                }
                android.util.Log.d("RP_IO", "loadProject OP OK: coreW=$coreW, coreH=$coreH, nativeLayers=${ReverieCoreBridge.layerCount()}, animated=$animated")
            } else {
                mainHandler.post { if (referenceSession == referenceLoadSession) referenceLoading = false }
                android.util.Log.e("RP_IO", "loadProject OP FAILED for ${file.absolutePath}")
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
    if (srcFile.renameTo(destFile)) referenceStore.move(srcFile, destFile)
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
    if (!File(p.filePath).exists()) forgetLocalReferences(File(p.filePath))
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
        if (file.renameTo(target)) referenceStore.move(file, target)
    }
    refreshProjects()
}

internal fun PaintViewModel.duplicateProject(p: com.reverie.paint.model.Project) {
    duplicateProjectFiles(listOf(p), single = true)
}

internal fun PaintViewModel.deleteProjects(projects: List<com.reverie.paint.model.Project>) {
    projects.forEach { p ->
        if (p.isFolder) {
            val dir = File(p.filePath)
            if (dir.exists() && dir.isDirectory) {
                dir.deleteRecursively()
            }
        } else {
            val file = File(p.filePath)
            if (file.exists()) file.delete()
        }
        if (!File(p.filePath).exists()) forgetLocalReferences(File(p.filePath))
    }
    refreshProjects()
}

internal fun PaintViewModel.duplicateProjects(projects: List<com.reverie.paint.model.Project>) {
    duplicateProjectFiles(projects, single = false)
}

private fun PaintViewModel.duplicateProjectFiles(
    projects: List<com.reverie.paint.model.Project>,
    single: Boolean,
) {
    if (isBlockingLoading) return
    val sources = projects.filter { !it.isFolder }
    if (sources.isEmpty()) return
    val copySuffix = if (LanguageManager.isChinese()) "副本" else "Copy"
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_copying_progress, 1, sources.size)
    viewModelScope.launch {
        var count = 0
        var lastName = ""
        try {
            for ((index, project) in sources.withIndex()) {
                blockingLoadingMessage = getString(R.string.project_copying_progress, index + 1, sources.size)
                val copiedName = withContext(Dispatchers.IO) {
                    val source = File(project.filePath)
                    var target: File? = null
                    var ownsTarget = false
                    try {
                        check(source.isFile && source.length() > 0L) { "Source is missing or empty" }
                        val directory = source.parentFile ?: error("Source has no parent directory")
                        var name = "${project.name} $copySuffix"
                        var counter = 2
                        // Reserve the destination without replacing an existing artwork.
                        while (true) {
                            val candidate = File(directory, "$name.${source.extension}")
                            if (candidate.createNewFile()) {
                                target = candidate
                                ownsTarget = true
                                break
                            }
                            name = "${project.name} $copySuffix ${counter++}"
                        }
                        source.inputStream().use { input ->
                            requireNotNull(target).outputStream().use { output -> input.copyTo(output) }
                        }
                        name
                    } catch (e: Exception) {
                        if (ownsTarget) target?.delete()
                        android.util.Log.e("RP_PROJECT", "duplicateProject failed", e)
                        null
                    }
                }
                if (copiedName != null) {
                    count++
                    lastName = copiedName
                }
            }
            if (count < sources.size) {
                showActionToast(R.string.project_copy_result, R.drawable.ic_copy, count, sources.size - count)
            } else if (single) {
                showActionToast(R.string.toast_project_duplicate_created, R.drawable.ic_copy, lastName)
            } else {
                showActionToast(R.string.gallery_toast_batch_duplicated, R.drawable.ic_copy, count)
            }
        } finally {
            isBlockingLoading = false
            blockingLoadingMessage = ""
            refreshProjects()
        }
    }
}

fun shareProjectFile(context: android.content.Context, p: com.reverie.paint.model.Project) {
    if (p.isFolder) return
    val file = File(p.filePath)
    if (!file.exists()) return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "revp" -> "application/x-reveriepaint"
            "kra" -> "application/x-krita"
            "psd" -> "image/vnd.adobe.photoshop"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "*/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享作品: ${p.name}"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareProjectFiles(context: android.content.Context, projects: List<com.reverie.paint.model.Project>) {
    val nonFolders = projects.filter { !it.isFolder }
    if (nonFolders.isEmpty()) return
    if (nonFolders.size == 1) {
        shareProjectFile(context, nonFolders[0])
        return
    }
    try {
        val uris = ArrayList<android.net.Uri>()
        for (p in nonFolders) {
            val file = File(p.filePath)
            if (file.exists()) {
                uris.add(
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                )
            }
        }
        if (uris.isEmpty()) return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "批量分享 (${uris.size} 个作品)"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "批量分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
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
    var dpi = 300
    var layerCount = 1
    var selectedLayerIndex = -1
    var colorModeStr = "RGB 8位"
    var previewPath = ""
    var hasRec = false
    var masterPath = ""
    var isAnimation = false

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
                    dpi = json.optInt("dpi", 300)
                    strokes = json.optInt("strokeCount", 0)
                    elapsed = json.optLong("elapsedSeconds", 0L)
                    colorModeStr = json.optString("colorMode", "RGB 8位")
                    layerCount = json.optJSONArray("layers")?.length() ?: 1
                    selectedLayerIndex = json.optInt("selectedLayerIndex", json.optInt("activeLayerIndex", json.optInt("currentLayerIndex", -1)))
                    masterPath = json.optString("masterFilePath", "")
                    // 动画项目: 任一图层带关键帧通道 (meta.layers[].animated)
                    isAnimation = json.optJSONArray("layers")?.let { arr ->
                        (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("animated", false) == true }
                    } ?: false
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

    val isAutoSaveFile = f.name.contains(".autosave")
    val displayName = if (f.nameWithoutExtension.endsWith(".autosave")) {
        f.nameWithoutExtension.removeSuffix(".autosave")
    } else {
        f.nameWithoutExtension
    }

    return com.reverie.paint.model
        .Project(
            name = displayName,
            width = w,
            height = h,
            dpi = dpi,
            filePath = f.absolutePath,
            previewPath = previewPath,
            strokeCount = strokes,
            elapsedSeconds = elapsed,
            lastModified = f.lastModified(),
            layerCount = layerCount,
            selectedLayerIndex = selectedLayerIndex,
            colorMode = colorModeStr,
            fileSize = f.length(),
            isFolder = false,
            hasRecording = hasRec,
            isAutoSaved = isAutoSaveFile,
            masterFilePath = masterPath,
            isAnimation = isAnimation,
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

private var projectsRefreshJob: Job? = null

/**
 * 刷新工程列表。
 *
 * 目录扫描与 .revp 元数据解析要走 ZIP 解压和磁盘 IO, 因此整体放到 IO 线程执行,
 * 结果回主线程赋值; 刷新期间保留旧列表 (不置空), 数据就绪后一次性替换。
 *
 * 旧实现同步跑在主线程: 保存刚写出的文件 mtime 已变、元数据缓存必然失效, 于是
 * **每次保存之后都要在主线程重解析一遍刚写出的 .revp**(大画布可达几十 MB),
 * 工程一多就是明显的卡顿 —— 这是"保存很慢"体感的重要来源之一。
 */
internal fun PaintViewModel.refreshProjects() {
    projectsRefreshJob?.cancel()
    projectsRefreshJob =
        viewModelScope.launch {
            val folder = currentFolder
            if (folder != null && folder.isFolder && !File(folder.filePath).exists()) {
                currentFolder = null
            }
            val list = withContext(Dispatchers.IO) { buildProjectList() }
            projects = list
        }
}

/** 纯 IO 部分: 扫描目录并把每个工程解析成 [Project] (带 mtime/size 元数据缓存) */
private fun PaintViewModel.buildProjectList(): List<com.reverie.paint.model.Project> {
    val rootDir = projectDir()
    if (!rootDir.exists()) return emptyList()

    // If currently inside a folder, read projects inside that subfolder
    val folder = currentFolder
    if (folder != null && folder.isFolder) {
        val dir = File(folder.filePath)
        if (!dir.exists()) return emptyList()
        val files: Array<File> =
            dir
                .listFiles { f: File -> f.isFile && f.extension.lowercase() in listOf("revp", "kra", "png") }
                ?.sortedByDescending { it.lastModified() }
                ?.toTypedArray() ?: emptyArray()

        return files.map { parseProjectFromFile(it) }
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

    // 扫描未正常消费的自动保存异常恢复草稿 (清后台或崩溃后恢复)
    val autoDir = autoSaveDir()
    val autoFiles: Array<File> =
        autoDir
            .listFiles { f: File -> f.isFile && f.extension.lowercase() in listOf("revp", "kra") }
            ?: emptyArray()

    for (autoFile in autoFiles) {
        val parsedAuto = parseProjectFromFile(autoFile)
        val autoProject = parsedAuto.copy(isAutoSaved = true)
        val existingIdx = if (autoProject.masterFilePath.isNotBlank()) {
            list.indexOfFirst { !it.isFolder && it.filePath == autoProject.masterFilePath }
        } else {
            val candidateMaster = File(rootDir, "${autoProject.name}.revp").absolutePath
            list.indexOfFirst { !it.isFolder && it.filePath == candidateMaster }
        }
        if (existingIdx != -1) {
            val existing = list[existingIdx]
            if (autoFile.lastModified() >= existing.lastModified) {
                list[existingIdx] = autoProject
            }
        } else {
            list.add(0, autoProject)
        }
    }

    return list
}

/**
 * Export the current artwork into various formats:
 * PNG, JPG, PSD, TIFF, KRA, REVP
 */
internal fun PaintViewModel.exportDocument(
    format: String,
    targetFile: java.io.File,
    embedAuthor: Boolean = true,
    onSuccess: (java.io.File) -> Unit,
    onError: (String) -> Unit = {},
) {
    val fmt = format.lowercase()
    runCore(render = false) {
        if (!embedAuthor || !authorProfile.enabled) {
            ReverieCoreBridge.setAuthorProfile("")
        } else {
            ReverieCoreBridge.setAuthorProfile(authorProfile.toJson())
        }

        val ok = try {
            when (fmt) {
                "png" -> {
                    ReverieCoreBridge.savePng(targetFile.absolutePath)
                }

                "jpg", "jpeg" -> {
                    val tempPng = java.io.File(appContext.cacheDir, "temp_jpg_${System.currentTimeMillis()}.png")
                    try {
                        if (ReverieCoreBridge.savePng(tempPng.absolutePath)) {
                            val bmp = android.graphics.BitmapFactory.decodeFile(tempPng.absolutePath)
                            if (bmp != null) {
                                // JPEG 不支持 Alpha 通道, 在纯白背景上合成后输出
                                val solidBmp = android.graphics.Bitmap.createBitmap(
                                    bmp.width,
                                    bmp.height,
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                )
                                val canvas = android.graphics.Canvas(solidBmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                canvas.drawBitmap(bmp, 0f, 0f, null)
                                bmp.recycle()
                                targetFile.outputStream().use { out ->
                                    solidBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                solidBmp.recycle()

                                if (embedAuthor && authorProfile.enabled && authorProfile.isNotEmpty()) {
                                    try {
                                        val exif = android.media.ExifInterface(targetFile.absolutePath)
                                        val author = authorProfile.name.trim().ifEmpty { authorProfile.nickname.trim() }
                                        if (author.isNotEmpty()) {
                                            exif.setAttribute(android.media.ExifInterface.TAG_ARTIST, author)
                                        }
                                        if (authorProfile.copyright.trim().isNotEmpty()) {
                                            exif.setAttribute(android.media.ExifInterface.TAG_COPYRIGHT, authorProfile.copyright.trim())
                                        }
                                        exif.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "ReveriePaint")
                                        exif.saveAttributes()
                                    } catch (_: Exception) {}
                                }
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    } finally {
                        tempPng.delete()
                    }
                }

                "psd" -> {
                    ReverieCoreBridge.exportPsd(targetFile.absolutePath)
                }

                "kra" -> {
                    ReverieCoreBridge.saveKra(targetFile.absolutePath)
                }

                "revp" -> {
                    val authorSnippet = if (embedAuthor && authorProfile.enabled && authorProfile.isNotEmpty()) {
                        ", \"author\": ${authorProfile.toJson()}"
                    } else ""
                    val extraJson =
                        """
                        {
                            "strokeCount": $totalStrokes,
                            "elapsedSeconds": $elapsedSeconds,
                            "createdTime": $canvasCreatedTime,
                            "colorMode": "$colorMode",
                            "layerCount": ${layers.size},
                            "selectedLayerIndex": $currentLayerIndex,
                            "activeLayerIndex": $currentLayerIndex
                            $authorSnippet
                        }
                        """.trimIndent()
                    ReverieCoreBridge.saveRevp(targetFile.absolutePath, extraJson, recorder.serialize())
                }

                "webp" -> {
                    val tempPng = java.io.File(appContext.cacheDir, "temp_webp.png")
                    if (ReverieCoreBridge.savePng(tempPng.absolutePath)) {
                        val bmp = android.graphics.BitmapFactory.decodeFile(tempPng.absolutePath)
                        if (bmp != null) {
                            targetFile.outputStream().use { out ->
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                                } else {
                                    @Suppress("DEPRECATION")
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP, 100, out)
                                }
                            }
                            tempPng.delete()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
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
        } finally {
            if (authorProfile.enabled) {
                ReverieCoreBridge.setAuthorProfile(authorProfile.toJson())
            }
        }
        mainHandler.post {
            if (ok && targetFile.exists() && targetFile.length() > 0) {
                onSuccess(targetFile)
            } else {
                onError(getString(R.string.toast_project_export_failed, format))
            }
        }
    }
}

/**
 * Export PNG/JPEG/WEBP image directly to Android MediaStore System Gallery
 */
internal fun PaintViewModel.exportImageToGallery(
    format: String,
    embedAuthor: Boolean = true,
    onSuccess: (android.net.Uri) -> Unit,
    onError: (String) -> Unit = {},
) {
    val fmt = format.lowercase()
    val isJpg = fmt == "jpg" || fmt == "jpeg"
    val isWebp = fmt == "webp"
    val mimeType = when {
        isJpg -> "image/jpeg"
        isWebp -> "image/webp"
        else -> "image/png"
    }
    val ext = when {
        isJpg -> "jpg"
        isWebp -> "webp"
        else -> "png"
    }
    val fileName = "${docName}_${System.currentTimeMillis()}.$ext"

    val tempFile = java.io.File(appContext.cacheDir, fileName)
    exportDocument(
        format = ext,
        targetFile = tempFile,
        embedAuthor = embedAuthor,
        onSuccess = { file ->
            try {
                val resolver = appContext.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/ReveriePaint")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    tempFile.delete()
                    onSuccess(uri)
                } else {
                    onError(getString(R.string.toast_project_gallery_create_failed))
                }
            } catch (e: Exception) {
                onError(getString(R.string.toast_project_gallery_save_failed, e.message ?: ""))
            }
        },
        onError = onError,
    )
}

internal fun PaintViewModel.goHome() {
    resetProjectReferences()
    recorder.endSession()
    stopPaintingTimer()
    // 动画播放与状态镜像: 离开绘画页彻底停止并重置动画状态, 避免耗电和跨画布状态残留
    resetAnimationState()
    // 释放 native 文档: g_core 是进程级单例, 不释放则旧 KisImage (所有图层
    // tile) 一直驻留内存, 重新打开时新旧文档共存推高峰值内存 (大文档被 LMK
    // 杀进程)。排队到渲染线程, FIFO 保证已排队的引擎操作先完成; 之后残留的
    // 渲染/保存调用在 C++ 侧空文档防护中安全返回。
    runCore(render = false) { ReverieCoreBridge.closeDocument() }
    currentProjectFile = null
    docName = ""
    isModified = false
    initialStrokeCount = 0
    totalStrokes = 0
    lastAutoSaveTimeMs = 0L
    // Safety net: clear any stuck loading overlay (e.g. if a runCore op threw
    // before after() could set isBlockingLoading = false)
    isBlockingLoading = false
    currentPage = Page.HOME
    refreshProjects()
}

internal fun PaintViewModel.goCreate() {
    stopPaintingTimer()
    resetAnimationState()
    currentPage = Page.CREATE
}

internal fun PaintViewModel.generateNextProjectName(): String {
    val existingNames = mutableSetOf<String>()
    val root = projectDir()
    root.walkTopDown().filter { it.isFile }.forEach { f ->
        existingNames.add(f.nameWithoutExtension.lowercase())
    }
    val autoDir = autoSaveDir()
    autoDir.walkTopDown().filter { it.isFile }.forEach { f ->
        val cleanName = if (f.nameWithoutExtension.endsWith(".autosave")) {
            f.nameWithoutExtension.removeSuffix(".autosave")
        } else {
            f.nameWithoutExtension
        }
        existingNames.add(cleanName.lowercase())
    }
    projects.forEach { p ->
        existingNames.add(p.name.lowercase())
        p.items.forEach { sub -> existingNames.add(sub.name.lowercase()) }
    }
    if (docName.isNotBlank()) {
        existingNames.add(docName.lowercase())
    }

    var index = 1
    val isZh = LanguageManager.isChinese()
    val candidate = if (isZh) "未命名作品" else "Untitled Artwork"
    if (!existingNames.contains(candidate.lowercase())) {
        return candidate
    }
    while (existingNames.contains("$candidate $index".lowercase())) {
        index++
    }
    return "$candidate $index"
}

internal fun PaintViewModel.startPainting(
    w: Int,
    h: Int,
    dpi: Int = 300,
    name: String? = null,
    initialBitmap: android.graphics.Bitmap? = null,
    initialSnapshotFile: java.io.File? = null,
    animation: Boolean = false,
    animationFps: Int = DEFAULT_ANIMATION_FPS,
) {
    resetProjectReferences()
    val actualName = name?.ifBlank { null } ?: generateNextProjectName()
    currentProjectFile = null // Reset so new artwork won't overwrite previous project file
    docName = actualName
    isModified = false
    lastAutoSaveTimeMs = 0L
    totalStrokes = 0
    elapsedSeconds = 0L
    canvasCreatedTime = System.currentTimeMillis()
    stopPaintingTimer()
    resetAnimationState()
    currentPage = Page.PAINTING
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_creating_canvas)
    runCore(
        after = {
            initialStrokeCount = 0
            totalStrokes = 0
            isModified = false
            docWidth = w
            docHeight = h
            docDpi = dpi
            docName = actualName
            checkBrushSizeLimit()
            isBlockingLoading = false
            startPaintingTimer()
            if (animation) {
                anim.enabled = true
                anim.panelOpen = true
                anim.framerate = animationFps
                anim.currentTime = 0
                anim.length = 1
                anim.revision++
                syncAnimationFromNativeAfter()
            } else {
                resetAnimationState()
            }
            collapsedGroupNames = emptySet()
        },
    ) {
        try {
            if (ReverieCoreBridge.newDocument(w, h)) {
                coreW = w
                coreH = h
                renderW = w
                renderH = h
                displayBufferInvalid = true
                mainHandler.post { savedSelections.clear() }
                if (!LanguageManager.isChinese()) {
                    if (ReverieCoreBridge.layerCount() >= 2) {
                        ReverieCoreBridge.setLayerName(0, "Background")
                        ReverieCoreBridge.setLayerName(1, "Paint Layer 1")
                    }
                }
                if (initialBitmap != null) {
                    val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(initialBitmap)
                    try {
                        ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                    } finally {
                        stampBmp.recycle()
                    }
                    ReverieCoreBridge.clearUndoHistory()
                }
                syncLayersFromNative()
                ReverieCoreBridge.setUndoLimit(maxUndoSteps)
                ReverieCoreBridge.setBrushColor(brushColor)
                ReverieCoreBridge.setBrushSecondaryColor(brushSecondaryColor)
                if (animation) {
                    // 动画画布: 为最上面的可动画图层建关键帧通道并设帧率
                    nativeInitAnimation(animationFps)
                }
                recorder.beginSession(w, h, snapshotSource = initialSnapshotFile, snapshotTempDir = recSessionDir())
            }
        } finally {
            if (initialBitmap != null && !initialBitmap.isRecycled) {
                initialBitmap.recycle()
            }
            initialSnapshotFile?.delete()
        }
    }
}

/** Enter the replay page for a project that embeds a recording. */
internal fun PaintViewModel.goReplay(p: com.reverie.paint.model.Project) {
    recorder.endSession()
    stopPaintingTimer()
    // 回放页与动画互斥: 停播并重置动画状态, 避免后台渲染和画布覆盖
    resetAnimationState()
    currentPage = Page.REPLAY
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_preparing_replay)
    var session: ReplaySession? = null
    runCore(
        after = {
            isBlockingLoading = false
            if (session == null) {
                showActionToast(R.string.toast_project_no_replay_data, R.drawable.ic_clock)
                goHome()
                return@runCore
            }
            val s = session!!
            replaySession = s
            docWidth = s.docW
            docHeight = s.docH
            checkBrushSizeLimit()
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
        }
    }
}

internal fun PaintViewModel.openProject(p: com.reverie.paint.model.Project) {
    loadProject(p)
}

internal fun PaintViewModel.refreshDisplay() {
    scheduleRender(immediate = true)
}

private var cachedBuiltInNames: Set<String>? = null

internal fun PaintViewModel.getBuiltInBrushNames(): Set<String> {
    cachedBuiltInNames?.let { return it }
    val names = try {
        appContext.assets.list("paintoppresets")?.map { it.removeSuffix(".kpp") }?.toSet() ?: emptySet()
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
    brushPresetsLoaded = true
    isBrushPresetsLoading = true
    loadToolOptions()
    loadViewSettings()
    loadShortcuts()
    loadBrushParams()
    // 把 assets 里几百个笔刷预设与笔刷资源 (.gbr/.gih/.png/.svg, 可达几十 MB)
    // 拷进 filesDir 是纯 IO: 原先同步跑在调用线程 (MainActivity 的
    // LaunchedEffect, 即主线程), 首启或清数据之后的启动卡顿主要来自这里。
    // 改为 IO 线程执行, 完成后再回主线程走后面的流程 (Compose 状态写入必须在
    // 主线程, JNI 读取仍在渲染线程)。
    viewModelScope.launch {
        try {
            val dirs = withContext(Dispatchers.IO) {
                val d = copyBundledBrushAssets()
                // 必须在引擎加载任何预设之前清掉历史重复参数键
                migrateDuplicatedPresetParams(d.first)
                d
            }
            loadBrushPresetsAfterAssets(dirs.first, dirs.second)
        } catch (t: Throwable) {
            android.util.Log.e("ReveriePaint", "loadBrushPresets IO failed", t)
            isBrushPresetsLoading = false
        }
    }
}

/**
 * assets -> filesDir 的一次性拷贝 (纯 IO; 已存在的文件直接跳过, 可重复调用)。
 * 返回 (预设目录, 笔刷资源目录)。
 */
private fun PaintViewModel.copyBundledBrushAssets(): Pair<File, File> {
    val dir = java.io.File(appContext.filesDir, "paintoppresets")
    val brushDir = java.io.File(appContext.filesDir, "brushes")
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val needsFactoryRestore = !prefs.getBoolean("brush_kpp_factory_restored_v5", false)
    val lastInstalledVersion = prefs.getInt("brush_assets_installed_version", -1)
    val isVersionMatched = lastInstalledVersion == com.reverie.paint.BuildConfig.VERSION_CODE

    // 若当前版本已拷贝过资源且目标目录健全，直接秒级快速返回，免去对 500+ 个 assets 文件的解压与遍历检查
    if (!needsFactoryRestore && isVersionMatched && dir.exists() && brushDir.exists()) {
        val presetCount = dir.list()?.size ?: 0
        val brushCount = brushDir.list()?.size ?: 0
        if (presetCount >= 100 && brushCount >= 100) {
            return dir to brushDir
        }
    }

    val assets = appContext.assets
    try {
        if (!dir.exists()) dir.mkdirs()
        for (name in assets.list("paintoppresets") ?: emptyArray()) {
            val target = java.io.File(dir, name)
            if (!target.exists() || needsFactoryRestore) {
                assets.open("paintoppresets/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        if (needsFactoryRestore) {
            prefs.edit().putBoolean("brush_kpp_factory_restored_v5", true).apply()
        }
    } catch (e: Exception) {
        android.util.Log.e("ReveriePaint", "preset copy failed", e)
    }
    android.util.Log.d("ReveriePaint", "loadBrushPresets files=" + (dir.list()?.size ?: -1))
    // Copy the bundled brush resource files (.gbr/.gih/.png/.svg) from
    // assets to filesDir once, so presets can resolve their
    // brush_definition files via the shared KisLocalStrokeResources.
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

    val patternDir = java.io.File(appContext.filesDir, "patterns")
    try {
        if (!patternDir.exists()) patternDir.mkdirs()
        for (name in assets.list("patterns") ?: emptyArray()) {
            val target = java.io.File(patternDir, name)
            if (!target.exists()) {
                assets.open("patterns/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
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
        try {
            android.util.Log.d("ReveriePaint", "loadBrushPresets assign=${list.size}")
            // Re-apply the persisted user order (same policy as reloadBrushPresets)
            val rank = brushOrder.withIndex().associate { it.value to it.index }
            val ordered =
                if (rank.isEmpty()) list.toList()
                else list.toList().sortedBy { rank[it.name] ?: (rank.size + it.index) }
            brushPresets = ordered
            if (ordered.isNotEmpty()) {
                val defaultDrawingPreset = ordered.firstOrNull {
                    it.name == "b)_Basic-5_Size_default"
                } ?: ordered.firstOrNull {
                    it.name == "b)_Basic-5_Size_Opacity"
                } ?: ordered.firstOrNull {
                    it.group == "基础" && !it.name.startsWith("a)_Eraser", ignoreCase = true) && !it.name.contains("Eraser", ignoreCase = true)
                } ?: ordered.firstOrNull {
                    it.group != "橡皮擦" && !it.name.startsWith("a)_Eraser", ignoreCase = true) && !it.name.contains("Eraser", ignoreCase = true)
                } ?: ordered[0]

                val defaultEraserPreset = ordered.firstOrNull {
                    it.name == "a)_Eraser_Circle"
                } ?: ordered.firstOrNull {
                    it.name == "Eraser_circle"
                } ?: ordered.firstOrNull {
                    it.group == "橡皮擦"
                } ?: ordered[0]

                val savedToolId = prefs().getString("current_tool_id", "brush") ?: "brush"
                val isEraserTool = savedToolId == "eraser"
                val fallbackPreset = if (isEraserTool) defaultEraserPreset else defaultDrawingPreset

                val savedToolState = toolBrushStates[savedToolId]
                // Saved preset indices are NATIVE-table indices; validate by item presence
                val targetIndex =
                    if (savedToolState != null && ordered.any { it.index == savedToolState.presetIndex }) {
                        val candidate = ordered.first { it.index == savedToolState.presetIndex }
                        val isCandidateEraser = candidate.group == "橡皮擦" ||
                                candidate.name.startsWith("a)_Eraser", ignoreCase = true) ||
                                candidate.name.contains("Eraser", ignoreCase = true)
                        if (!isEraserTool && isCandidateEraser) {
                            fallbackPreset.index
                        } else {
                            savedToolState.presetIndex
                        }
                    } else {
                        val savedPresetIdx = prefs().getInt("last_brush_preset_index", -1)
                        if (savedPresetIdx >= 0 && ordered.any { it.index == savedPresetIdx }) {
                            val candidate = ordered.first { it.index == savedPresetIdx }
                            val isCandidateEraser = candidate.group == "橡皮擦" ||
                                    candidate.name.startsWith("a)_Eraser", ignoreCase = true) ||
                                    candidate.name.contains("Eraser", ignoreCase = true)
                            if (!isEraserTool && isCandidateEraser) {
                                fallbackPreset.index
                            } else {
                                savedPresetIdx
                            }
                        } else {
                            fallbackPreset.index
                        }
                    }
                applyTool(savedToolId)
                selectBrushPreset(targetIndex)
            }
        } finally {
            isBrushPresetsLoading = false
        }
    }) {
        try {
            android.util.Log.d("ReveriePaint", "loadBrushPresets runCore start")
            val nrb = ReverieCoreBridge.loadBrushResources(brushDir.absolutePath)
            android.util.Log.d("ReveriePaint", "loadBrushResources count=$nrb")
            val patternDir = java.io.File(appContext.filesDir, "patterns")
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
        } catch (t: Throwable) {
            android.util.Log.e("ReveriePaint", "loadBrushPresets runCore op failed", t)
        }
    }
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

                val success = when (ext) {
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
        }
    }
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
    if (ext in listOf("kpp", "bundle", "abr", "gbr", "gih")) return true
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
                            eName.endsWith(".kpp", ignoreCase = true) ||
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
    if (mime != null && (mime.contains("kpp") || mime.contains("bundle") || mime.contains("photoshop-brush") || mime.contains("paintoppreset"))) {
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


