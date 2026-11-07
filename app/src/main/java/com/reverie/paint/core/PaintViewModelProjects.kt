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
import com.reverie.paint.model.FrontBufferQuiet
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
import java.util.Locale
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
            ?: run {
                val targetDir = currentFolder?.let { File(it.filePath) }?.takeIf { it.exists() && it.isDirectory }
                    ?: projectDir()
                File(targetDir, "$name.revp")
            }
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
    flushPendingTimelapseCapture()
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_saving_progress)
    var savedFile: File? = null
    // 退出/手动保存前在 UI 线程先取方向：进引擎线程后读触摸视图不可靠，保存失败不执行退出回调（原有路径）。
    val orientationJson = canvasOrientationJsonFragment()
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
            // 只上报"自上次上报以来"的增量 (工程累计值在 recordCreationSession 内部求差)
            recordCreationSession()
            currentProjectFile = targetFile.absolutePath
            // Renaming from the save dialog moves this working association; do not let the
            // previous file and the renamed artwork share a mutable reference selection.
            previousReferencePath?.takeIf { it != targetFile.absolutePath }?.let { oldPath ->
                referenceStore.forgetPath(oldPath)
                val oldFile = File(oldPath)
                renameTimelapseData(oldFile, targetFile)
            }
            referenceStore.bind(targetFile.absolutePath, referenceProfile)
            initialStrokeCount = totalStrokes
            isModified = false
            selectionDirty = false
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
            for (layerId in active3DLayers.keys) {
                ensure3DLayerClearedInCore(layerId)
            }
            refreshProjects()
            maybeAutoBackup()
            onComplete?.invoke()
        },
    ) {
        if (active3DLayers.isNotEmpty()) {
            commitAllActive3DLayersSync()
        }

        val layers3DJson = active3DLayers.values.joinToString(",") { st ->
            val idx = layers.indexOfFirst { it.id == st.layerId }
            val name = if (idx in 0 until layers.size) layers[idx].name else ""
            st.copy(layerIndex = idx, layerName = name).toJsonString()
        }
        val extraJson =
            """
            {
                $orientationJson
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size},
                "dpi": $docDpi,
                "panelDefaults": ${panelDefaultsJson()},
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex,
                "collapsedGroups": [${collapsedGroupsForSave().joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }}],
                "collapsedPanels": [${collapsedPanelIds.joinToString(",")}],
                "active3DLayers": [$layers3DJson]
            }
            """.trimIndent()
        val saveT0 = android.os.SystemClock.elapsedRealtime()
        val saved = ReverieCoreBridge.saveRevp(finalFile.absolutePath, extraJson, null)
        PerfTrace.event(
            "saveProject",
            "ms" to (android.os.SystemClock.elapsedRealtime() - saveT0),
            "layers" to layers.size.toLong(),
            "mb" to (finalFile.length() / (1024L * 1024L)),
        )
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
    // 自动保存同步写方向，未手动保存就切换/崩溃时也能按最后快照恢复。
    val orientationJson = canvasOrientationJsonFragment()

    runCore(
        render = false,
        after = {
            lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
            strokesSinceLastAutoSave = 0
            hasPendingMajorOp = false
            selectionDirty = false // 这次快照已经把 selectedLayerIndex 一起写进去了
            isAutoSaving = false
            // 自动保存同样是"把这段作画落定"的时刻: 顺手把增量记进每日统计, 否则
            // 进程被系统杀掉时这一段时长会永远丢掉 (只记增量, 不会重复计数)
            recordCreationSession()
            if (isPeriodic && autoSaveToastEnabled) {
                showActionToast(R.string.toast_project_autosaved, R.drawable.ic_save)
            }
        },
    ) {
        val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        val isTransformToolActive = currentToolId == com.reverie.paint.model.Tool.TRANSFORM.id || transformPreviewBitmap != null
        if (tv?.isInteracting == true || tv?.isTransformActive == true || isTransformToolActive) {
            return@runCore
        }

        val layers3DJson = active3DLayers.values.joinToString(",") { st ->
            val idx = layers.indexOfFirst { it.id == st.layerId }
            val name = if (idx in 0 until layers.size) layers[idx].name else ""
            st.copy(layerIndex = idx, layerName = name).toJsonString()
        }
        val extraJson =
            """
            {
                $orientationJson
                "strokeCount": $totalStrokes,
                "elapsedSeconds": $elapsedSeconds,
                "createdTime": $canvasCreatedTime,
                "colorMode": "$colorMode",
                "layerCount": ${layers.size},
                "isAutoSave": true,
                "masterFilePath": "$currentMasterPath",
                "docName": "$name",
                "dpi": $docDpi,
                "panelDefaults": ${panelDefaultsJson()},
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex,
                "collapsedGroups": [${collapsedGroupsForSave().joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }}],
                "collapsedPanels": [${collapsedPanelIds.joinToString(",")}],
                "active3DLayers": [$layers3DJson]
            }
            """.trimIndent()
        val backupFile = File(autoSaveFile.parentFile, "${autoSaveFile.name}.bak")
        val oldFileSize = if (autoSaveFile.exists()) autoSaveFile.length() else 0L
        if (oldFileSize > 50_000L) {
            try {
                autoSaveFile.copyTo(backupFile, overwrite = true)
            } catch (_: Throwable) {}
        }
        val saveStartTime = System.currentTimeMillis()
        val saved = ReverieCoreBridge.saveRevpAsync(autoSaveFile.absolutePath, extraJson, null)
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
                            if (backupFile.exists() && oldFileSize > 100_000L && autoSaveFile.length() < oldFileSize * 0.3) {
                                android.util.Log.w("RP_IO", "autoSaveRevpAsync: size dropped drastically (${oldFileSize} -> ${autoSaveFile.length()}), backup preserved at ${backupFile.name}")
                            } else if (backupFile.exists()) {
                                backupFile.delete()
                            }
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
        val layers3DJson = active3DLayers.values.joinToString(",") { st ->
            val idx = layers.indexOfFirst { it.id == st.layerId }
            val name = if (idx in 0 until layers.size) layers[idx].name else ""
            st.copy(layerIndex = idx, layerName = name).toJsonString()
        }
        // 崩溃抢救同样带方向：恢复的工程按抢救快照的方向打开，与自动保存一致。
        val orientationJson = canvasOrientationJsonFragment()
        val extraJson =
            """
            {
                $orientationJson
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
                "panelDefaults": ${panelDefaultsJson()},
                "selectedLayerIndex": $currentLayerIndex,
                "activeLayerIndex": $currentLayerIndex,
                "active3DLayers": [$layers3DJson]
            }
            """.trimIndent()
        val saved = ReverieCoreBridge.saveRevp(emergencyFile.absolutePath, extraJson, null)
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
    // 若当前为未保存过的新建工程，丢弃作画时同步清理本次录制的延时摄影残留帧
    val masterFile = currentProjectFile?.let { File(it) }
    if (masterFile == null || !masterFile.exists()) {
        purgeTimelapseFrames()
    }
    // 2. 退出到画廊主页
    goHome()
}

internal fun PaintViewModel.loadProject(p: com.reverie.paint.model.Project) {
    android.util.Log.d("RP_IO", "loadProject START: name=${p.name}, path=${p.filePath}, isAutoSaved=${p.isAutoSaved}")
    if (quickShapeDraft?.nativeOriginalActive == true) {
        touchCancel()
        quickShapeDraft = null
        activeQuickShape = null
        isQuickShapeEditing = false
    }
    stopPaintingTimer()
    resetAnimationState()
    resetProjectReferences(loading = true)
    releaseReclaimableCaches(aggressive = true)
    // 注: 这里曾试过在加载前放开 displayBitmap 以压内存峰值, 但会造成首帧空白与
    // 同一工程二次进入失败, 已撤掉; 内存峰值改由 releaseReclaimableCaches 处理。
    val referenceLoadSession = referenceSession
    // 进画面前先作废上一份文档的瓦片网格, 并把尺寸镜像同步成新文档的声明尺寸:
    // 否则首帧 CanvasView.onSizeChanged 会拿旧 coreW/coreH 算 fitScale, 与渲染线程的
    // newDocument(w, h) 并发覆写 renderW/renderH, 画布按旧尺寸拼出新文档的瓦片。
    discardTileGridForDocumentSwitch()
    val preW = if (p.width > 0) p.width else docWidth
    val preH = if (p.height > 0) p.height else docHeight
    if (preW > 0 && preH > 0) {
        docWidth = preW
        docHeight = preH
        coreW = preW
        coreH = preH
        renderW = preW
        renderH = preH
    }
    // Navigate to painting page first, then show loading overlay while reading native file
    currentPage = Page.PAINTING
    isBlockingLoading = true
    blockingLoadingMessage = getString(R.string.project_loading_progress)
    val isRecovered = p.isAutoSaved || p.filePath.contains(".autosave")
    docName = p.name
    lastAutoSaveTimeMs = 0L
    val masterFile = if (p.masterFilePath.isNotBlank()) File(p.masterFilePath) else File(projectDir(), "${p.name}.revp")
    currentProjectFile = if (!isRecovered) {
        p.filePath
    } else if (masterFile.exists() && masterFile.absolutePath != p.filePath) {
        masterFile.absolutePath
    } else {
        null
    }

    var loadedCollapsedGroups: Set<String> = emptySet()
    var loadedCollapsedPanels: Set<Long> = emptySet()
    // 旧工程 extraJson 没有 collapsedPanels 字段: 首次打开按"每格默认折叠"补一次;
    // 字段存在 (哪怕空数组 = 用户全部展开过) 则尊重存档, 不再回填
    var loadedCollapsedPanelsPresent = false
    var loadedCanvasOrientation: com.reverie.paint.model.CanvasOrientation? = null
    var loaded3DStates: List<com.reverie.paint.model.Model3DState> = emptyList()
    var loadedPanelDefaults: com.reverie.paint.model.PanelDefaults? = null
    var documentLoaded = false
    var isHealed = false
    val loadT0 = android.os.SystemClock.elapsedRealtime()
    runCore(
        after = {
            PerfTrace.event(
                "loadProject",
                "ms" to (android.os.SystemClock.elapsedRealtime() - loadT0),
                "layers" to layers.size.toLong(),
                "w" to docWidth.toLong(),
                "h" to docHeight.toLong(),
            )
            if (!documentLoaded) {
                Breadcrumbs.record("IO", "loadProject FAILED for: ${p.name}")
                isBlockingLoading = false
                showActionToast(R.string.toast_project_import_unsupported, R.drawable.ic_alert_triangle)
                // 加载失败不留旧方向。
                pendingCanvasOrientation = null
                return@runCore
            }
            Breadcrumbs.record("IO", "loadProject SUCCESS: ${p.name} (healed=$isHealed)")
            if (referenceSession == referenceLoadSession) {
                if (documentLoaded) restoreLocalProjectReferences(p.filePath)
                else referenceLoading = false
            }
            // 折叠状态要等图层表同步后再还原 (见下方 syncLayersFromNative 之后)
            // 只在加载成功后交出方向；无记录时 pending 为 null，走默认横向。
            pendingCanvasOrientation = loadedCanvasOrientation
            // 先清空上一份文档的全部分格会话镜像 (含 panelCrossMaskStamp 等跨格缓存),
            // 再还原本工程的折叠状态与分格默认值
            resetPanelSessionState()
            collapsedPanelIds = loadedCollapsedPanels
            panelDefaults = loadedPanelDefaults ?: DEFAULT_PANEL_DEFAULTS
            initialStrokeCount = p.strokeCount
            totalStrokes = p.strokeCount
            isModified = isRecovered || isHealed // 异常恢复或自愈的工程标记为未保存
            selectionDirty = false // 刚读出来的选中层就是磁盘上的状态
            docWidth = if (coreW > 0) coreW else p.width
            docHeight = if (coreH > 0) coreH else p.height
            docDpi = if (p.dpi > 0) p.dpi else 300
            docName = p.name
            checkBrushSizeLimit()

            val masterFile = if (p.masterFilePath.isNotBlank()) File(p.masterFilePath) else File(projectDir(), "${p.name}.revp")
            currentProjectFile = if (!isRecovered) {
                p.filePath
            } else if (masterFile.exists() && masterFile.absolutePath != p.filePath) {
                masterFile.absolutePath
            } else {
                null
            }

            elapsedSeconds = p.elapsedSeconds
            // 统计基线对齐存档值: 重开工程时不该把历史时长再记进今天一次
            resetStatsBaseline()
            canvasCreatedTime = if (p.lastModified > 0) p.lastModified else System.currentTimeMillis()
            colorMode = p.colorMode
            syncLayersFromNative()
            // 折叠状态按"名字/名字@序号"还原成层 id 键 —— 必须晚于 syncLayersFromNative,
            // 否则 layers 还是旧文档/空表, 一个都对不上
            applyCollapsedGroupsFromFile(loadedCollapsedGroups)
            // 文档加载完成后重建分格镜像 (工程里的分格结构/侧表元数据随 maindoc.xml 恢复)
            refreshPanels()
            // 旧档没有 collapsedPanels: 现有格补一次默认折叠 (之后用户展开会写回字段, 不再回填)
            if (!loadedCollapsedPanelsPresent && panels.isNotEmpty()) {
                collapsedPanelIds = collapsedPanelIds + panels.map { it.panelId }
            }
            // 工程刚载入时图层面板可能早已打开: 面板打开时的强制刷新跑在 loadRevp 之前,
            // 那一刻 layerCount() 还是 0 (或旧文档), 之后没人再触发, 面板就停在透明缩略图上,
            // 直到第一次落笔 (afterLayerMutation) 才恢复。这里补一次。
            refreshLayerThumbs(force = layerThumbsVisible)
            val targetLayer = p.selectedLayerIndex
            if (targetLayer in 0 until layers.size && currentLayerIndex != targetLayer) {
                currentLayerIndex = targetLayer
            }

            // 恢复 3D 参考图层 (保证在 syncLayersFromNative 更新 layers 之后准确绑定)
            if (loaded3DStates.isNotEmpty()) {
                val map = HashMap<Long, com.reverie.paint.model.Model3DState>()
                for (st in loaded3DStates) {
                    val target = layers.find { it.name == st.layerName && it.name.isNotBlank() && !map.containsKey(it.id) }
                        ?: (if (st.layerIndex in 0 until layers.size && !map.containsKey(layers[st.layerIndex].id)) layers[st.layerIndex] else null)
                        ?: layers.find { it.name.contains("3D", ignoreCase = true) && !map.containsKey(it.id) }
                    if (target != null) {
                        map[target.id] = st.copy(layerId = target.id, layerIndex = target.index, layerName = target.name)
                    }
                }
                active3DLayers = map
                // 清空底层 Krita 颜料图层的烘焙静态像素，交由实时 120Hz 视口 Canvas3DDrawer 渲染，避免重影叠画
                for (layerId in map.keys) {
                    ensure3DLayerClearedInCore(layerId)
                }
                // 后台预热模型解析, 避免打开工程后第一帧在主线程解析 GLB 卡顿
                for (st in map.values) {
                    prewarm3DModel(st)
                }
            } else {
                active3DLayers = emptyMap()
            }
            frontBufferQuietUntilMs = FrontBufferQuiet.onDocumentLoaded(
                android.os.SystemClock.uptimeMillis(),
                PaintViewModel.NEW_CANVAS_FRONT_BUFFER_QUIET_MS,
            )
            startPaintingTimer()
            if (isBrushPresetsLoading || !brushPresetsLoaded || brushPresets.isEmpty()) {
                loadBrushPresets()
                isBlockingLoading = true
                blockingLoadingMessage = getString(R.string.brush_presets_loading)
            } else {
                isBlockingLoading = false
                val targetTool = currentToolId.ifBlank { "brush" }
                applyTool(targetTool, force = true)
            }
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
                                    val panelArr = json.optJSONArray("collapsedPanels")
                                    if (panelArr != null) {
                                        loadedCollapsedPanelsPresent = true
                                        val panelSet = mutableSetOf<Long>()
                                        for (i in 0 until panelArr.length()) {
                                            val v = panelArr.optLong(i, Long.MIN_VALUE)
                                            if (v != Long.MIN_VALUE) panelSet.add(v)
                                        }
                                        loadedCollapsedPanels = panelSet
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
                if (file.extension.equals("revp", ignoreCase = true)) {
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
                                // 只读二值方向，不恢复缩放/平移/视图中心；缺失或非法值返回 null。
                                loadedCanvasOrientation = com.reverie.paint.model.CanvasOrientation.fromJsonValue(
                                    json.optString(com.reverie.paint.model.CanvasOrientation.META_KEY),
                                )
                                val arr3D = json.optJSONArray("active3DLayers")
                                if (arr3D != null && arr3D.length() > 0) {
                                    val parsedStates = ArrayList<com.reverie.paint.model.Model3DState>()
                                    for (k in 0 until arr3D.length()) {
                                        val obj = arr3D.optJSONObject(k)
                                        if (obj != null) {
                                            val st = com.reverie.paint.model.Model3DState.fromJsonObject(obj)
                                            parsedStates.add(st)
                                        }
                                    }
                                    loaded3DStates = parsedStates
                                }
                                // 分格默认样式 (无则恢复为出厂默认)
                                json.optJSONObject("panelDefaults")?.let { pdObj ->
                                    loadedPanelDefaults = com.reverie.paint.model.PanelDefaults(
                                        borderWidth = pdObj.optDouble("borderWidth", DEFAULT_PANEL_DEFAULTS.borderWidth.toDouble()).toFloat(),
                                        borderColor = pdObj.optInt("borderColor", DEFAULT_PANEL_DEFAULTS.borderColor),
                                        hGap = pdObj.optDouble("hGap", DEFAULT_PANEL_DEFAULTS.hGap.toDouble()).toFloat(),
                                        vGap = pdObj.optDouble("vGap", DEFAULT_PANEL_DEFAULTS.vGap.toDouble()).toFloat(),
                                        fillColor = pdObj.optInt("fillColor", DEFAULT_PANEL_DEFAULTS.fillColor),
                                        fillEnabled = pdObj.optBoolean("fillEnabled", DEFAULT_PANEL_DEFAULTS.fillEnabled),
                                        cornerRadius = pdObj.optDouble("cornerRadius", DEFAULT_PANEL_DEFAULTS.cornerRadius.toDouble()).toFloat(),
                                        cropEnabled = pdObj.optBoolean("cropEnabled", true),
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (targetLayer in 0 until nLayers) {
                    ReverieCoreBridge.setCurrentLayer(targetLayer)
                }

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

internal fun PaintViewModel.createStackWithProjects(
    name: String,
    projects: List<com.reverie.paint.model.Project>,
) {
    val root = projectDir()
    val folder = File(root, name.trim())
    if (!folder.exists()) {
        folder.mkdirs()
    }
    moveProjectsToFolder(projects, name.trim())
}

internal fun generateDefaultStackName(
    existingProjects: List<com.reverie.paint.model.Project>,
    baseName: String,
    indexedFormat: String,
): String {
    val existingNames = existingProjects.filter { it.isFolder }.map { it.name }.toSet()
    if (!existingNames.contains(baseName)) {
        return baseName
    }
    var i = 1
    while (true) {
        val candidate = String.format(Locale.getDefault(), indexedFormat, i)
        if (!existingNames.contains(candidate)) {
            return candidate
        }
        i++
    }
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
    if (srcFile.renameTo(destFile)) {
        referenceStore.move(srcFile, destFile)
        renameTimelapseData(oldFile = srcFile, newFile = destFile)
    }
    refreshProjects()
}

internal fun PaintViewModel.moveProjectsToFolder(
    projects: List<com.reverie.paint.model.Project>,
    targetFolderName: String?,
) {
    val root = projectDir()
    val destDir = if (targetFolderName.isNullOrBlank()) root else File(root, targetFolderName)
    if (!destDir.exists()) destDir.mkdirs()
    projects.forEach { p ->
        if (!p.isFolder) {
            val srcFile = File(p.filePath)
            if (srcFile.exists()) {
                val destFile = File(destDir, srcFile.name)
                if (srcFile.renameTo(destFile)) {
                    referenceStore.move(srcFile, destFile)
                    renameTimelapseData(oldFile = srcFile, newFile = destFile)
                }
            }
        }
    }
    refreshProjects()
}

internal fun PaintViewModel.setFolderCover(
    folder: com.reverie.paint.model.Project,
    coverProject: com.reverie.paint.model.Project,
) {
    if (!folder.isFolder) return
    val folderDir = File(folder.filePath)
    if (!folderDir.exists() || !folderDir.isDirectory) return
    val coverFile = File(folderDir, ".cover")
    val projectFile = File(coverProject.filePath)
    coverFile.writeText(projectFile.name)
    refreshProjects()
}

internal fun PaintViewModel.clearFolderCover(
    folder: com.reverie.paint.model.Project,
) {
    if (!folder.isFolder) return
    val coverFile = File(folder.filePath, ".cover")
    if (coverFile.exists()) coverFile.delete()
    refreshProjects()
}

internal fun PaintViewModel.deleteProject(p: com.reverie.paint.model.Project) {
    if (p.isFolder) {
        val dir = File(p.filePath)
        if (dir.exists() && dir.isDirectory) {
            dir.walkTopDown().forEach { subFile ->
                if (subFile.isFile && subFile.extension.lowercase() in listOf("revp", "kra")) {
                    val subProj = com.reverie.paint.model.Project(
                        name = subFile.nameWithoutExtension.removeSuffix(".autosave"),
                        filePath = subFile.absolutePath,
                    )
                    deleteTimelapseData(subProj)
                }
            }
            dir.deleteRecursively()
        }
        cleanTimelapseFolder(dir.name)
    } else {
        val file = File(p.filePath)
        if (file.exists()) file.delete()
        deleteTimelapseData(p)
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
        if (file.renameTo(target)) {
            referenceStore.move(file, target)
            if (p.isFolder) {
                renameTimelapseFolder(p.name, newName.trim())
            } else {
                renameTimelapseData(oldFile = file, newFile = target)
            }
        }
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
                dir.walkTopDown().forEach { subFile ->
                    if (subFile.isFile && subFile.extension.lowercase() in listOf("revp", "kra")) {
                        val subProj = com.reverie.paint.model.Project(
                            name = subFile.nameWithoutExtension.removeSuffix(".autosave"),
                            filePath = subFile.absolutePath,
                        )
                        deleteTimelapseData(subProj)
                    }
                }
                dir.deleteRecursively()
            }
            cleanTimelapseFolder(dir.name)
        } else {
            val file = File(p.filePath)
            if (file.exists()) file.delete()
            deleteTimelapseData(p)
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
                        val srcFrames = getTimelapseFramesDir(project)
                        val dstProject = com.reverie.paint.model.Project(name = name, filePath = target.absolutePath)
                        val dstFrames = getTimelapseFramesDir(dstProject)
                        if (srcFrames?.exists() == true && dstFrames != null) {
                            srcFrames.copyRecursively(dstFrames, overwrite = true)
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
                val framesDir = getTimelapseFramesDir(f)
                hasRec = framesDir?.exists() == true && framesDir.isDirectory &&
                    (framesDir.list { _, name -> name.startsWith("frame_") && (name.endsWith(".webp") || name.endsWith(".png")) }?.isNotEmpty() == true)
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
            try {
                com.reverie.paint.ui.widget.RecentProjectWidgetHelper.notifyAllWidgets(appContext)
            } catch (_: Throwable) {}
        }
}

/** 纯 IO 部分: 扫描目录并把每个工程解析成 [Project] (带 mtime/size 元数据缓存) */
private fun PaintViewModel.buildProjectList(): List<com.reverie.paint.model.Project> {
    migrateLegacyTimelapseStorage()
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

        val coverFile = File(dir, ".cover")
        val coverName = if (coverFile.exists()) coverFile.readText().trim() else null

        val folderList = files.map { file ->
            val p = parseProjectFromFile(file)
            if (coverName != null && file.name == coverName) {
                p.copy(isFolderCover = true)
            } else {
                p
            }
        }.toMutableList()

        val autoDir = autoSaveDir()
        val autoFiles: Array<File> =
            autoDir
                .listFiles { f: File -> f.isFile && f.extension.lowercase() in listOf("revp", "kra") }
                ?: emptyArray()

        for (autoFile in autoFiles) {
            val parsedAuto = parseProjectFromFile(autoFile)
            val autoProject = parsedAuto.copy(isAutoSaved = true)
            val matchesFolder = if (autoProject.masterFilePath.isNotBlank()) {
                File(autoProject.masterFilePath).parentFile?.absolutePath == dir.absolutePath
            } else {
                false
            }
            if (matchesFolder) {
                val existingIdx = folderList.indexOfFirst { !it.isFolder && it.filePath == autoProject.masterFilePath }
                if (existingIdx != -1) {
                    val existing = folderList[existingIdx]
                    if (autoFile.lastModified() >= existing.lastModified) {
                        folderList[existingIdx] = autoProject
                    }
                } else {
                    folderList.add(0, autoProject)
                }
            }
        }
        return folderList
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

            val coverFile = File(entry, ".cover")
            val chosenCoverName = if (coverFile.exists()) coverFile.readText().trim() else null

            val subProjects = subFiles.map { file ->
                val p = parseProjectFromFile(file)
                if (chosenCoverName != null && file.name == chosenCoverName) {
                    p.copy(isFolderCover = true)
                } else {
                    p
                }
            }
            val coverProject = subProjects.firstOrNull { it.isFolderCover }
            val orderedSubProjects = if (coverProject != null) {
                listOf(coverProject) + (subProjects - coverProject)
            } else {
                subProjects
            }
            val topThumb = orderedSubProjects.firstOrNull()?.previewPath ?: ""
            val topModified = maxOf(entry.lastModified(), subProjects.maxOfOrNull { it.lastModified } ?: 0L)

            list.add(
                com.reverie.paint.model.Project(
                    name = entry.name,
                    filePath = entry.absolutePath,
                    previewPath = topThumb,
                    lastModified = topModified,
                    isFolder = true,
                    folderPath = entry.absolutePath,
                    items = orderedSubProjects,
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
        val belongsToSubfolder = autoProject.masterFilePath.isNotBlank() &&
            File(autoProject.masterFilePath).parentFile?.absolutePath != rootDir.absolutePath
        if (belongsToSubfolder) {
            continue
        }
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

private fun PaintViewModel.burnInVectorPanelsToFile(targetFile: java.io.File, isJpeg: Boolean) {
    if (panels.isEmpty()) return
    val bmp = android.graphics.BitmapFactory.decodeFile(targetFile.absolutePath) ?: return
    val mutableBmp = if (bmp.isMutable) bmp else bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    if (mutableBmp != bmp) bmp.recycle()
    val canvas = android.graphics.Canvas(mutableBmp)
    val defaults = panelDefaults
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val docW = docWidth.coerceAtLeast(1).toFloat()
    val docH = docHeight.coerceAtLeast(1).toFloat()
    val sx = mutableBmp.width / docW
    val sy = mutableBmp.height / docH

    for (m in panels) {
        val style = m.meta.style
        val r = (style.cornerRadius ?: defaults.cornerRadius).toFloat()
        val bw = (style.borderWidth ?: defaults.borderWidth).toFloat() * sx
        val bc = style.borderColor ?: defaults.borderColor

        val points = m.meta.quad.roundedCorners(r)
        val path = android.graphics.Path()
        points.forEachIndexed { i, pt ->
            val px = pt.x.toFloat() * sx
            val py = pt.y.toFloat() * sy
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()

        if (bw > 0f) {
            paint.strokeWidth = bw
            paint.color = bc
            canvas.drawPath(path, paint)
        }
    }

    targetFile.outputStream().use { out ->
        if (isJpeg) {
            mutableBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        } else {
            mutableBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }
    mutableBmp.recycle()
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
        if (active3DLayers.isNotEmpty()) {
            commitAllActive3DLayersSync()
        }
        if (!embedAuthor || !authorProfile.enabled) {
            ReverieCoreBridge.setAuthorProfile("")
        } else {
            ReverieCoreBridge.setAuthorProfile(authorProfile.toJson())
        }

        val ok = try {
            when (fmt) {
                "png" -> {
                    val ok = ReverieCoreBridge.savePng(targetFile.absolutePath)
                    if (ok) {
                        burnInVectorPanelsToFile(targetFile, isJpeg = false)
                    }
                    ok
                }

                "jpg", "jpeg" -> {
                    val tempPng = java.io.File(appContext.cacheDir, "temp_jpg_${System.currentTimeMillis()}.png")
                    try {
                        if (ReverieCoreBridge.savePng(tempPng.absolutePath)) {
                            burnInVectorPanelsToFile(tempPng, isJpeg = false)
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
                            "panelDefaults": ${panelDefaultsJson()},
                            "selectedLayerIndex": $currentLayerIndex,
                            "activeLayerIndex": $currentLayerIndex
                            $authorSnippet
                        }
                        """.trimIndent()
                    ReverieCoreBridge.saveRevp(targetFile.absolutePath, extraJson, null)
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
            for (layerId in active3DLayers.keys) {
                ensure3DLayerClearedInCore(layerId)
            }
            if (ok && targetFile.exists() && targetFile.length() > 0) {
                recordExport()
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
    stopPaintingTimer()
    // 动画播放与状态镜像: 离开绘画页彻底停止并重置动画状态, 避免耗电和跨画布状态残留
    resetAnimationState()
    // 分格镜像同样是文档级缓存: 回主页即清, 下次进入任何画布都不会残留旧格框
    resetPanelSessionState()
    // 释放 native 文档: g_core 是进程级单例, 不释放则旧 KisImage (所有图层
    // tile) 一直驻留内存, 重新打开时新旧文档共存推高峰值内存 (大文档被 LMK
    // 杀进程)。排队到渲染线程, FIFO 保证已排队的引擎操作先完成; 之后残留的
    // 渲染/保存调用在 C++ 侧空文档防护中安全返回。
    runCore(render = false) { ReverieCoreBridge.closeDocument() }
    currentProjectFile = null
    resetTimelapseCaptureState()
    // 退出即清待恢复方向；丢弃退出不写盘，方向回落到上次保存值。
    pendingCanvasOrientation = null
    docName = ""
    isModified = false
    selectionDirty = false
    recordCreationSession()
    initialStrokeCount = 0
    totalStrokes = 0
    resetStatsBaseline()
    lastAutoSaveTimeMs = 0L
    // Safety net: clear any stuck loading overlay (e.g. if a runCore op threw
    // before after() could set isBlockingLoading = false)
    isBlockingLoading = false
    active3DLayers = emptyMap()
    show3DPosePanel = false
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
    if (quickShapeDraft?.nativeOriginalActive == true) {
        touchCancel()
        quickShapeDraft = null
        activeQuickShape = null
        isQuickShapeEditing = false
    }
    resetProjectReferences()
    val actualName = name?.ifBlank { null } ?: generateNextProjectName()
    val targetDir = currentFolder?.let { File(it.filePath) }?.takeIf { it.exists() && it.isDirectory }
        ?: projectDir()
    currentProjectFile = File(targetDir, "$actualName.revp").absolutePath
    // 新建画布：显式清空同名残留延时摄影帧目录，杜绝继承任何历史同名残留帧
    purgeTimelapseFrames()
    recordCanvasCreated()
    // 新建工程无记录：默认横向（0°），不继承上一工程的方向。
    pendingCanvasOrientation = null
    docName = actualName
    isModified = false
    selectionDirty = false
    lastAutoSaveTimeMs = 0L
    totalStrokes = 0
    elapsedSeconds = 0L
    resetStatsBaseline()
    canvasCreatedTime = System.currentTimeMillis()
    stopPaintingTimer()
    resetAnimationState()
    // 同 loadProject: 进画面前作废旧网格, 尺寸镜像按新画布先同步好。
    discardTileGridForDocumentSwitch()
    if (w > 0 && h > 0) {
        docWidth = w
        docHeight = h
        coreW = w
        coreH = h
        renderW = w
        renderH = h
    }
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
            // 加载层关掉之后用户才能落笔。前缓冲要再静默一段时间，见 frontBufferQuietUntilMs。
            frontBufferQuietUntilMs = FrontBufferQuiet.onDocumentLoaded(
                android.os.SystemClock.uptimeMillis(),
                PaintViewModel.NEW_CANVAS_FRONT_BUFFER_QUIET_MS,
            )
            startPaintingTimer()
            if (isBrushPresetsLoading || !brushPresetsLoaded || brushPresets.isEmpty()) {
                loadBrushPresets()
                isBlockingLoading = true
                blockingLoadingMessage = getString(R.string.brush_presets_loading)
            } else {
                isBlockingLoading = false
                val targetTool = currentToolId.ifBlank { "brush" }
                applyTool(targetTool, force = true)
            }
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
            collapsedGroupKeys = emptySet()
            // 新文档无分格树: 清空旧文档的全部分格会话镜像, 避免旧格框残留在新画布
            resetPanelSessionState()
            refreshPanels()
            active3DLayers = emptyMap()
            show3DPosePanel = false
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
                    // 动画画布: 为最上面的可动画图层建关键帧通道并设帧率。
                    // 建工程阶段不计入撤销历史 (帧率现在是可撤销的文档设置,
                    // 否则新文档一打开就自带一步"改帧率")
                    ReverieCoreBridge.setUndoCaptureEnabled(false)
                    try {
                        nativeInitAnimation(animationFps)
                    } finally {
                        ReverieCoreBridge.setUndoCaptureEnabled(true)
                    }
                }
            }
        } finally {
            if (initialBitmap != null && !initialBitmap.isRecycled) {
                initialBitmap.recycle()
            }
            initialSnapshotFile?.delete()
        }
    }
}

/** 进入延时摄影序列帧回放页 */
internal fun PaintViewModel.goReplay(p: com.reverie.paint.model.Project? = null) {
    if (p == null) {
        flushPendingTimelapseCapture()
    }
    stopPaintingTimer()
    resetAnimationState()
    replayProject = p
    currentPage = Page.REPLAY
}

/** 退出回放页 */
internal fun PaintViewModel.exitReplay() {
    replayProject = null
    if (docWidth > 0 && currentProjectFile != null) {
        currentPage = Page.PAINTING
    } else {
        goHome()
    }
}

internal fun PaintViewModel.openProject(p: com.reverie.paint.model.Project) {
    loadProject(p)
}

internal fun PaintViewModel.refreshDisplay() {
    scheduleRender(immediate = true)
}

