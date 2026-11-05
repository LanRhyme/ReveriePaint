/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

data class DailyActivity(
    val date: String, // "yyyy-MM-dd"
    val strokes: Int = 0,
    val durationSeconds: Long = 0L,
    val canvasesCreated: Int = 0,
) {
    /**
     * GitHub-like activity level (0 to 4)
     * Level 0: No activity
     * Level 1: Light activity (< 10m or < 300 strokes)
     * Level 2: Moderate activity (< 30m or < 1000 strokes)
     * Level 3: Active (< 60m or < 2500 strokes)
     * Level 4: Highly active (>= 60m or >= 2500 strokes)
     */
    val level: Int
        get() {
            if (strokes <= 0 && durationSeconds <= 0L && canvasesCreated <= 0) return 0
            val score = (strokes / 250f) + (durationSeconds / 300f) + (canvasesCreated * 1.5f)
            return when {
                score <= 0f -> 0
                score < 2f -> 1
                score < 6f -> 2
                score < 14f -> 3
                else -> 4
            }
        }
}

data class CreationStatsSummary(
    val totalStrokes: Long = 0L,
    val totalDurationSeconds: Long = 0L,
    val totalCanvases: Int = 0,
    val totalExports: Int = 0,
    val appLaunches: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeDaysPastYear: Int = 0,
    val dailyActivities: Map<String, DailyActivity> = emptyMap(),
    val topAspectRatios: List<Pair<String, Int>> = emptyList(),
    val topBrushes: List<Pair<String, Int>> = emptyList(),
)
