/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.*

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.updateAuthorProfile(profile: com.reverie.paint.model.AuthorProfile) {
    authorProfile = profile
    val updated = authorProfilePresets.map {
        if (it.id == activeAuthorProfileId) it.copy(profile = profile) else it
    }
    if (updated.none { it.id == activeAuthorProfileId }) {
        authorProfilePresets = listOf(
            com.reverie.paint.model.AuthorProfilePreset(
                id = activeAuthorProfileId,
                name = "默认档案",
                profile = profile,
            ),
        )
    } else {
        authorProfilePresets = updated
    }
    persistAuthorProfiles()
    syncAuthorProfileToCore()
}

internal fun PaintViewModel.switchAuthorProfile(presetId: String) {
    val target = authorProfilePresets.firstOrNull { it.id == presetId } ?: return
    activeAuthorProfileId = target.id
    authorProfile = target.profile
    persistAuthorProfiles()
    syncAuthorProfileToCore()
}

internal fun PaintViewModel.createAuthorProfilePreset(name: String, baseProfile: com.reverie.paint.model.AuthorProfile = authorProfile) {
    val newId = java.util.UUID.randomUUID().toString()
    val trimmedName = name.trim().ifBlank { "预设 ${authorProfilePresets.size + 1}" }
    val newPreset = com.reverie.paint.model.AuthorProfilePreset(
        id = newId,
        name = trimmedName,
        profile = baseProfile,
    )
    authorProfilePresets = authorProfilePresets + newPreset
    activeAuthorProfileId = newId
    authorProfile = baseProfile
    persistAuthorProfiles()
    syncAuthorProfileToCore()
}

internal fun PaintViewModel.renameAuthorProfilePreset(presetId: String, newName: String) {
    val trimmed = newName.trim().ifBlank { return }
    authorProfilePresets = authorProfilePresets.map {
        if (it.id == presetId) it.copy(name = trimmed) else it
    }
    persistAuthorProfiles()
}

internal fun PaintViewModel.duplicateAuthorProfilePreset(presetId: String) {
    val source = authorProfilePresets.firstOrNull { it.id == presetId } ?: return
    val newId = java.util.UUID.randomUUID().toString()
    val copyPreset = com.reverie.paint.model.AuthorProfilePreset(
        id = newId,
        name = "${source.name} (副本)",
        profile = source.profile.copy(),
    )
    authorProfilePresets = authorProfilePresets + copyPreset
    persistAuthorProfiles()
}

internal fun PaintViewModel.deleteAuthorProfilePreset(presetId: String): Boolean {
    if (authorProfilePresets.size <= 1) return false
    val newPresets = authorProfilePresets.filterNot { it.id == presetId }
    authorProfilePresets = newPresets
    if (activeAuthorProfileId == presetId) {
        val next = newPresets.first()
        activeAuthorProfileId = next.id
        authorProfile = next.profile
        syncAuthorProfileToCore()
    }
    persistAuthorProfiles()
    return true
}

internal fun PaintViewModel.persistAuthorProfiles() {
    if (!isAppContextReady()) return
    try {
        appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("author_profile_json", authorProfile.toJson())
            .putString(
                "author_profile_presets_json",
                com.reverie.paint.model.AuthorProfilePreset.listToJson(authorProfilePresets),
            )
            .putString("active_author_profile_id", activeAuthorProfileId)
            .apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.checkOnboardingTrigger() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val completed = prefs.getBoolean("onboarding_completed_v1", false)
    if (!completed) {
        onboardingActive = true
        onboardingStep = 0
    }
}

internal fun PaintViewModel.dismissOnboarding(markCompleted: Boolean = true) {
    onboardingActive = false
    layerPanelMeasuredBounds = null
    allToolsPanelMeasuredBounds = null
    viewToolsMeasuredBounds = null
    if (markCompleted && isAppContextReady()) {
        val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed_v1", true).apply()
    }
}

internal fun PaintViewModel.startOnboarding() {
    onboardingStep = 0
    onboardingActive = true
}

internal fun PaintViewModel.recordLaunch() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val cur = prefs.getInt("launch_count", 0) + 1
    prefs.edit().putInt("launch_count", cur).apply()
    launchCount = cur
    exportCount = prefs.getInt("export_count", 0)
    createdCanvasCount = prefs.getInt("created_canvas_count", 0)
}

internal fun PaintViewModel.recordExport() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val cur = prefs.getInt("export_count", 0) + 1
    prefs.edit().putInt("export_count", cur).apply()
    exportCount = cur
}

internal fun PaintViewModel.recordCanvasCreated() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val cur = prefs.getInt("created_canvas_count", 0) + 1
    prefs.edit().putInt("created_canvas_count", cur).apply()
    createdCanvasCount = cur
    CreationStatsManager.recordCanvasCreated(appContext)
}

internal fun PaintViewModel.checkSponsorPromptTrigger() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val suppressed = prefs.getBoolean("sponsor_prompt_suppressed", false)
    if (suppressed) return
    val launches = prefs.getInt("launch_count", 0)
    val exports = prefs.getInt("export_count", 0)
    val nextPromptLaunch = prefs.getInt("next_sponsor_prompt_launch_count", 15)
    val nextPromptExport = prefs.getInt("next_sponsor_prompt_export_count", 5)
    if (launches >= nextPromptLaunch || exports >= nextPromptExport) {
        showSponsorPromptDialog = true
    }
}

internal fun PaintViewModel.postponeSponsorPrompt() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    val currentLaunches = prefs.getInt("launch_count", 0)
    val currentExports = prefs.getInt("export_count", 0)
    prefs.edit()
        .putInt("next_sponsor_prompt_launch_count", currentLaunches + 25)
        .putInt("next_sponsor_prompt_export_count", currentExports + 10)
        .apply()
    showSponsorPromptDialog = false
}

internal fun PaintViewModel.suppressSponsorPrompt() {
    if (!isAppContextReady()) return
    val prefs = appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
    prefs.edit().putBoolean("sponsor_prompt_suppressed", true).apply()
    showSponsorPromptDialog = false
}

internal fun PaintViewModel.isQuickActionActivated(action: com.reverie.paint.model.QuickAction): Boolean =
    when (action) {
        com.reverie.paint.model.QuickAction.LOCK_VIEW -> isViewTransformLocked
        com.reverie.paint.model.QuickAction.FLIP_H -> viewFlipX
        com.reverie.paint.model.QuickAction.FLIP_V -> viewFlipY
        com.reverie.paint.model.QuickAction.TOGGLE_ERASER -> currentToolId == "eraser"
        com.reverie.paint.model.QuickAction.ERASER_MODE -> isEraserMode
        com.reverie.paint.model.QuickAction.ALPHA_LOCK -> isCurrentLayerAlphaLocked()
        com.reverie.paint.model.QuickAction.DISABLE_TOUCH -> isCanvasTouchDisabled
        com.reverie.paint.model.QuickAction.SHADING_ASSIST -> shadingAssistDialogOpen
        else -> false
    }

internal fun PaintViewModel.syncAuthorProfileToCore() {
    runCore(render = false) {
        ReverieCoreBridge.setAuthorProfile(authorProfile.toJson())
    }
}

/**
 * 把"自上次上报以来新增"的笔画数与时长记进今日活跃数据。
 *
 * 参数是工程累计值 ([totalStrokes] / [elapsedSeconds])，这里与 [statsReportedStrokes] /
 * [statsReportedSeconds] 求差后只上报增量，并推进基线 —— 调用方可以是保存、自动保存、
 * 退出等任意时机，重复调用不会重复计数。
 */
internal fun PaintViewModel.recordCreationSession() {
    if (!isAppContextReady()) return
    val delta = com.reverie.paint.model.CreationStatsDelta.since(
        totalStrokes = totalStrokes,
        totalSeconds = elapsedSeconds,
        reportedStrokes = statsReportedStrokes,
        reportedSeconds = statsReportedSeconds,
    )
    if (delta.isEmpty) return
    CreationStatsManager.recordSession(appContext, delta.strokes, delta.seconds)
    statsReportedStrokes = totalStrokes
    statsReportedSeconds = elapsedSeconds
}

/** 清空每日创作统计 (设置页入口; 只清历史, 不影响当前工程计时)。 */
internal fun PaintViewModel.resetCreationStats() {
    if (!isAppContextReady()) return
    CreationStatsManager.reset(appContext)
}

/** 把统计基线对齐到当前工程状态 (打开工程 / 新建画布 / 关闭工程时调用)。 */
internal fun PaintViewModel.resetStatsBaseline() {
    statsReportedStrokes = totalStrokes
    statsReportedSeconds = elapsedSeconds
}

internal fun PaintViewModel.recordBrushUsageStats(brushName: String) {
    if (isAppContextReady()) {
        CreationStatsManager.recordBrushUsage(appContext, brushName)
    }
}
