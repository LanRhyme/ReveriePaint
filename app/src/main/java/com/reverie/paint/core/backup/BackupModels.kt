/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.backup

enum class BackupCategory(val id: String) {
    PROJECTS("projects"),
    AUTOSAVE("autosave"),
    BRUSHES("brushes"),
    PALETTES("palettes"),
    SETTINGS_LAYOUT("settings_layout"),
    SHORTCUTS_STYLUS("shortcuts_stylus"),
    REFERENCES_FONTS("references_fonts");

    companion object {
        fun fromIdOrNull(id: String): BackupCategory? = values().firstOrNull { it.id == id }
    }
}

data class BackupFileEntry(
    val relativePath: String,
    val size: Long,
    val sha256: String = "",
)

data class BackupManifest(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val categories: List<String> = emptyList(),
    val projects: List<BackupFileEntry> = emptyList(),
    val autosaves: List<BackupFileEntry> = emptyList(),
    val brushPresets: List<String> = emptyList(),
    val brushTips: List<String> = emptyList(),
    val brushPatterns: List<String> = emptyList(),
    val palettesCount: Int = 0,
    val shortcutsCount: Int = 0,
)

data class BackupInspectionResult(
    val manifest: BackupManifest,
    val existingProjectConflicts: List<String> = emptyList(),
    val existingBrushConflicts: List<String> = emptyList(),
)

enum class ConflictResolution {
    KEEP_BOTH_COPY,
    OVERWRITE,
    SKIP_EXISTING,
}

data class RestoreOptions(
    val selectedCategories: Set<BackupCategory>,
    val projectConflictResolution: ConflictResolution = ConflictResolution.KEEP_BOTH_COPY,
    val brushConflictResolution: ConflictResolution = ConflictResolution.SKIP_EXISTING,
)

data class RestoreOutcome(
    val restoredProjects: Int = 0,
    val restoredAutosaves: Int = 0,
    val restoredBrushes: Int = 0,
    val restoredPalettes: Boolean = false,
    val restoredPreferences: Boolean = false,
    val restoredShortcuts: Boolean = false,
    val restoredReferences: Int = 0,
    /** 并入本机 identity.json 的导入笔刷条目数（跨设备恢复后常用/分组靠它对上 id） */
    val restoredBrushIdentities: Int = 0,
    /** 因名字撞了本机另一支笔、或恢复后磁盘上没有对应预设文件而没并入的条目数 */
    val skippedBrushIdentities: Int = 0,
    /** brush_groups（分组/排序/工具槽）已并入 */
    val restoredBrushGroups: Boolean = false,
    val errors: List<String> = emptyList(),
)
