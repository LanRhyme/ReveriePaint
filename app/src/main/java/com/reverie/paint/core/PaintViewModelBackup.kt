/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.core.backup.BackupCategory
import com.reverie.paint.core.backup.BackupInspectionResult
import com.reverie.paint.core.backup.ConflictResolution
import com.reverie.paint.core.backup.LocalBackupEngine
import com.reverie.paint.core.backup.RestoreOptions
import com.reverie.paint.core.backup.RestoreOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class LocalBackupStatus {
    IDLE,
    EXPORTING,
    EXPORT_SUCCESS,
    EXPORT_FAILED,
    INSPECTING,
    READY_TO_RESTORE,
    RESTORING,
    RESTORE_SUCCESS,
    RESTORE_FAILED,
}

internal class LocalBackupState {
    var status by mutableStateOf(LocalBackupStatus.IDLE)
    var currentItem by mutableStateOf("")
    var doneCount by mutableIntStateOf(0)
    var totalCount by mutableIntStateOf(0)
    var errorMessage by mutableStateOf<String?>(null)

    var showExportDialog by mutableStateOf(false)
    var showRestoreDialog by mutableStateOf(false)

    var selectedExportCategories by mutableStateOf(BackupCategory.values().toSet())

    var pendingRestoreUri by mutableStateOf<Uri?>(null)
    var inspectionResult by mutableStateOf<BackupInspectionResult?>(null)
    var selectedRestoreCategories by mutableStateOf(emptySet<BackupCategory>())

    var projectConflictResolution by mutableStateOf(ConflictResolution.KEEP_BOTH_COPY)
    var brushConflictResolution by mutableStateOf(ConflictResolution.SKIP_EXISTING)

    var lastRestoreOutcome by mutableStateOf<RestoreOutcome?>(null)
}

internal fun PaintViewModel.openExportBackupDialog() {
    localBackupState.selectedExportCategories = BackupCategory.values().toSet()
    localBackupState.errorMessage = null
    localBackupState.status = LocalBackupStatus.IDLE
    localBackupState.showExportDialog = true
}

internal fun PaintViewModel.closeExportBackupDialog() {
    localBackupState.showExportDialog = false
}

internal fun PaintViewModel.closeRestoreBackupDialog() {
    localBackupState.showRestoreDialog = false
    localBackupState.pendingRestoreUri = null
    localBackupState.inspectionResult = null
}

internal fun PaintViewModel.executeExportBackup(uri: Uri) {
    if (!hasAppContext()) return
    val categories = localBackupState.selectedExportCategories
    if (categories.isEmpty()) return

    localBackupState.status = LocalBackupStatus.EXPORTING
    localBackupState.doneCount = 0
    localBackupState.totalCount = 0
    localBackupState.errorMessage = null

    viewModelScope.launch(Dispatchers.IO) {
        try {
            val appVersion = try {
                val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                pkgInfo.versionName ?: ""
            } catch (_: Exception) { "" }

            val outStream = appContext.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开目标备份文件")

            outStream.use { stream ->
                LocalBackupEngine.createBackup(
                    context = appContext,
                    selectedCategories = categories,
                    outputStream = stream,
                    appVersion = appVersion,
                    onProgress = { done, total, label ->
                        localBackupState.doneCount = done
                        localBackupState.totalCount = total
                        localBackupState.currentItem = label
                    },
                )
            }

            withContext(Dispatchers.Main) {
                localBackupState.status = LocalBackupStatus.EXPORT_SUCCESS
                localBackupState.showExportDialog = false
                showActionToast(R.string.backup_export_success_toast, R.drawable.ic_cloud)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                localBackupState.status = LocalBackupStatus.EXPORT_FAILED
                localBackupState.errorMessage = e.message ?: e.javaClass.simpleName
                showActionToast(R.string.backup_export_failed_toast, R.drawable.ic_cloud)
            }
        }
    }
}

internal fun PaintViewModel.startInspectBackup(uri: Uri) {
    if (!hasAppContext()) return
    localBackupState.status = LocalBackupStatus.INSPECTING
    localBackupState.errorMessage = null
    localBackupState.pendingRestoreUri = uri

    viewModelScope.launch(Dispatchers.IO) {
        try {
            val inStream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取备份文件")

            val result = inStream.use { stream ->
                LocalBackupEngine.inspectBackup(appContext, stream)
            }

            withContext(Dispatchers.Main) {
                localBackupState.inspectionResult = result
                val availableCategories = result.manifest.categories
                    .mapNotNull { BackupCategory.fromIdOrNull(it) }
                    .toSet()
                localBackupState.selectedRestoreCategories = availableCategories
                localBackupState.projectConflictResolution = ConflictResolution.KEEP_BOTH_COPY
                localBackupState.brushConflictResolution = ConflictResolution.SKIP_EXISTING
                localBackupState.status = LocalBackupStatus.READY_TO_RESTORE
                localBackupState.showRestoreDialog = true
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                localBackupState.status = LocalBackupStatus.RESTORE_FAILED
                localBackupState.errorMessage = e.message ?: e.javaClass.simpleName
                showActionToast(R.string.backup_inspect_failed_toast, R.drawable.ic_cloud)
            }
        }
    }
}

internal fun PaintViewModel.executeRestoreBackup() {
    val uri = localBackupState.pendingRestoreUri ?: return
    val selected = localBackupState.selectedRestoreCategories
    if (selected.isEmpty()) return
    if (!hasAppContext()) return

    localBackupState.status = LocalBackupStatus.RESTORING
    localBackupState.doneCount = 0
    localBackupState.totalCount = 0
    localBackupState.errorMessage = null

    val options = RestoreOptions(
        selectedCategories = selected,
        projectConflictResolution = localBackupState.projectConflictResolution,
        brushConflictResolution = localBackupState.brushConflictResolution,
    )

    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (selected.contains(BackupCategory.BRUSHES)) {
                com.reverie.paint.core.brushstore.BrushStoreBootstrap.beginReconcilePending(appContext.filesDir)
            }
            val inStream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取备份文件")

            val outcome = inStream.use { stream ->
                LocalBackupEngine.restoreBackup(
                    context = appContext,
                    inputStream = stream,
                    options = options,
                    onProgress = { done, total, label ->
                        localBackupState.doneCount = done
                        localBackupState.totalCount = total
                        localBackupState.currentItem = label
                    },
                )
            }

            // 引擎已把备份里的导入笔 id 表并进磁盘上的 identity.json；内存里的表要先重读，
            // 对账才会沿用备份里的 id（否则这些预设会被当成「全新文件」换一个新 id，常用/分组就对不上了）
            if (outcome.restoredBrushIdentities > 0) {
                com.reverie.paint.core.brushstore.BrushIdentity.open(
                    com.reverie.paint.core.brushstore.BrushStoreBootstrap.storeRoot(appContext.filesDir),
                )
            }
            if (com.reverie.paint.core.brushstore.BrushStoreBootstrap.hasReconcilePending(appContext.filesDir)) {
                com.reverie.paint.core.brushstore.BrushStoreBootstrap.reconcileDisk(appContext.filesDir)
            }

            withContext(Dispatchers.Main) {
                localBackupState.status = LocalBackupStatus.RESTORE_SUCCESS
                localBackupState.lastRestoreOutcome = outcome
                localBackupState.showRestoreDialog = false

                // 热刷新系统状态
                // 数位笔模块也有 VM 镜像、压感曲线及驱动状态，不能只刷新键盘绑定。
                if (outcome.restoredPreferences || outcome.restoredShortcuts) {
                    syncSettingsFromPrefs()
                }
                if (outcome.restoredShortcuts) {
                    loadShortcuts()
                }
                if (outcome.restoredPalettes) {
                    reloadPalettesFromPrefs()
                }
                if (outcome.restoredProjects > 0 || outcome.restoredAutosaves > 0) {
                    refreshProjects()
                }
                // force：不带 force 在笔刷已加载过时是空操作，内存里的常用/分组/工具槽还是旧的，
                // 下一次保存会把刚恢复的覆盖回去
                if (outcome.restoredBrushes > 0 || outcome.restoredBrushIdentities > 0 ||
                    outcome.restoredBrushGroups || outcome.restoredPreferences
                ) {
                    loadBrushPresets(force = true)
                }

                showActionToast(R.string.backup_restore_success_toast, R.drawable.ic_cloud)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                localBackupState.status = LocalBackupStatus.RESTORE_FAILED
                localBackupState.errorMessage = e.message ?: e.javaClass.simpleName
                showActionToast(R.string.backup_restore_failed_toast, R.drawable.ic_cloud)
            }
        }
    }
}
