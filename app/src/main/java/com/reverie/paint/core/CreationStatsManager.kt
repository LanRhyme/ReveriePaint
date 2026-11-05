/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import com.reverie.paint.model.CreationStatsSummary
import com.reverie.paint.model.DailyActivity
import com.reverie.paint.model.Project
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 创作数据与活跃热力图管理单例 (Creation Stats & Daily Activity Heatmap Manager)
 * 负责持久化每日作画时长、笔画数、画作数以及智能回溯既有画作数据
 */
object CreationStatsManager {
    private const val FILE_NAME = "creation_stats.json"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var cachedSummary: CreationStatsSummary? = null
    private var isBackfilled = false

    fun getTodayString(): String = dateFormat.format(Date())

    private fun getFile(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun loadRaw(context: Context): JSONObject {
        val file = getFile(context)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    fun saveRaw(context: Context, json: JSONObject) {
        try {
            val file = getFile(context)
            file.writeText(json.toString(), Charsets.UTF_8)
            cachedSummary = null
        } catch (_: Exception) {}
    }

    /**
     * 智能回溯既有画作库，预热历史活跃网格（仅首次执行一次）
     */
    @Synchronized
    fun backfillIfFirstTime(context: Context, projects: List<Project>) {
        val root = loadRaw(context)
        if (root.optBoolean("backfilled", false)) return

        val dailyObj = root.optJSONObject("daily") ?: JSONObject().also { root.put("daily", it) }
        val brushUsage = root.optJSONObject("brushUsage") ?: JSONObject().also { root.put("brushUsage", it) }

        for (p in projects) {
            val timestamp = p.lastModified
            if (timestamp <= 0L) continue
            val dateStr = dateFormat.format(Date(timestamp))

            val day = dailyObj.optJSONObject(dateStr) ?: JSONObject().also { dailyObj.put(dateStr, it) }
            val existingStrokes = day.optInt("strokes", 0)
            val existingDuration = day.optLong("durationSeconds", 0L)
            val existingCanvases = day.optInt("canvasesCreated", 0)

            day.put("strokes", existingStrokes + p.strokeCount)
            day.put("durationSeconds", existingDuration + p.elapsedSeconds)
            day.put("canvasesCreated", existingCanvases + 1)
        }

        root.put("backfilled", true)
        saveRaw(context, root)
    }

    /**
     * 记录本次绘画会话（落笔数与耗时增量）
     */
    @Synchronized
    fun recordSession(context: Context, addedStrokes: Int, addedDurationSeconds: Long) {
        if (addedStrokes <= 0 && addedDurationSeconds <= 0L) return
        val root = loadRaw(context)
        val dailyObj = root.optJSONObject("daily") ?: JSONObject().also { root.put("daily", it) }
        val todayStr = getTodayString()
        val day = dailyObj.optJSONObject(todayStr) ?: JSONObject().also { dailyObj.put(todayStr, it) }

        day.put("strokes", day.optInt("strokes", 0) + addedStrokes)
        day.put("durationSeconds", day.optLong("durationSeconds", 0L) + addedDurationSeconds)

        saveRaw(context, root)
    }

    /**
     * 记录新画布创建
     */
    @Synchronized
    fun recordCanvasCreated(context: Context) {
        val root = loadRaw(context)
        val dailyObj = root.optJSONObject("daily") ?: JSONObject().also { root.put("daily", it) }
        val todayStr = getTodayString()
        val day = dailyObj.optJSONObject(todayStr) ?: JSONObject().also { dailyObj.put(todayStr, it) }

        day.put("canvasesCreated", day.optInt("canvasesCreated", 0) + 1)
        saveRaw(context, root)
    }

    /**
     * 记录笔刷使用频次
     */
    @Synchronized
    fun recordBrushUsage(context: Context, brushName: String) {
        if (brushName.isBlank()) return
        val root = loadRaw(context)
        val brushUsage = root.optJSONObject("brushUsage") ?: JSONObject().also { root.put("brushUsage", it) }
        brushUsage.put(brushName, brushUsage.optInt(brushName, 0) + 1)
        saveRaw(context, root)
    }

    /**
     * 计算连续作画天数与最长连击天数
     */
    fun computeStreaks(activeDates: Set<String>, todayStr: String): Pair<Int, Int> {
        if (activeDates.isEmpty()) return Pair(0, 0)
        val cal = Calendar.getInstance()
        val todayDate = try { dateFormat.parse(todayStr) } catch (_: Exception) { null } ?: Date()
        cal.time = todayDate

        val todayActive = activeDates.contains(todayStr)
        if (!todayActive) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        var currentStreak = 0
        while (activeDates.contains(dateFormat.format(cal.time))) {
            currentStreak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        val sortedDates = activeDates.mapNotNull {
            try { dateFormat.parse(it) } catch (_: Exception) { null }
        }.sorted()

        var maxStreak = if (sortedDates.isNotEmpty()) 1 else 0
        var running = 1
        for (i in 1 until sortedDates.size) {
            val prev = sortedDates[i - 1]
            val curr = sortedDates[i]
            val diffDays = (curr.time - prev.time) / (1000 * 60 * 60 * 24)
            if (diffDays == 1L) {
                running++
                if (running > maxStreak) maxStreak = running
            } else if (diffDays > 1L) {
                running = 1
            }
        }
        return Pair(currentStreak, maxStreak)
    }

    /**
     * 生成创作数据看板全量统计摘要
     */
    @Synchronized
    fun getSummary(context: Context, projects: List<Project>): CreationStatsSummary {
        backfillIfFirstTime(context, projects)

        val root = loadRaw(context)
        val dailyObj = root.optJSONObject("daily") ?: JSONObject()
        val brushUsage = root.optJSONObject("brushUsage") ?: JSONObject()

        val dailyMap = mutableMapOf<String, DailyActivity>()
        var totalStrokes = 0L
        var totalDuration = 0L
        var totalCanvases = 0

        val keys = dailyObj.keys()
        while (keys.hasNext()) {
            val dateKey = keys.next()
            val dayJson = dailyObj.optJSONObject(dateKey) ?: continue
            val strokes = dayJson.optInt("strokes", 0)
            val duration = dayJson.optLong("durationSeconds", 0L)
            val canvases = dayJson.optInt("canvasesCreated", 0)

            dailyMap[dateKey] = DailyActivity(
                date = dateKey,
                strokes = strokes,
                durationSeconds = duration,
                canvasesCreated = canvases,
            )
            totalStrokes += strokes
            totalDuration += duration
            totalCanvases += canvases
        }

        // 结合现有画作补充工程库当前总量（若画作实际量更多）
        val actualProjectCount = projects.count { !it.isFolder }
        if (actualProjectCount > totalCanvases) {
            totalCanvases = actualProjectCount
        }

        val prefs = context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
        val appLaunches = prefs.getInt("launch_count", 0)
        val totalExports = prefs.getInt("export_count", 0)

        val todayStr = getTodayString()
        val activeDates = dailyMap.filter { it.value.level > 0 }.keys
        val (currentStreak, longestStreak) = computeStreaks(activeDates, todayStr)

        // 计算过去 365 天内的活跃天数
        val cal = Calendar.getInstance()
        cal.time = Date()
        cal.add(Calendar.DAY_OF_YEAR, -365)
        val oneYearAgo = cal.time
        val activeDaysPastYear = dailyMap.filter {
            val d = try { dateFormat.parse(it.key) } catch (_: Exception) { null }
            d != null && d.after(oneYearAgo) && it.value.level > 0
        }.size

        // 常用画幅尺寸统计
        val aspectMap = mutableMapOf<String, Int>()
        for (p in projects) {
            if (p.isFolder || p.width <= 0 || p.height <= 0) continue
            val ratio = simplifyRatio(p.width, p.height)
            aspectMap[ratio] = (aspectMap[ratio] ?: 0) + 1
        }
        val topAspects = aspectMap.toList().sortedByDescending { it.second }.take(4)

        // 常用笔刷统计
        val brushList = mutableListOf<Pair<String, Int>>()
        val bKeys = brushUsage.keys()
        while (bKeys.hasNext()) {
            val k = bKeys.next()
            brushList.add(k to brushUsage.optInt(k, 0))
        }
        val topBrushes = brushList.sortedByDescending { it.second }.take(5)

        return CreationStatsSummary(
            totalStrokes = totalStrokes,
            totalDurationSeconds = totalDuration,
            totalCanvases = totalCanvases,
            totalExports = totalExports,
            appLaunches = appLaunches,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            activeDaysPastYear = activeDaysPastYear,
            dailyActivities = dailyMap,
            topAspectRatios = topAspects,
            topBrushes = topBrushes,
        )
    }

    private fun simplifyRatio(w: Int, h: Int): String {
        if (w == h) return "1:1"
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(w, h)
        val sw = w / g
        val sh = h / g
        return if (sw in 1..21 && sh in 1..21) "$sw:$sh"
        else {
            val r = w.toFloat() / h.toFloat()
            when {
                kotlin.math.abs(r - 16f / 9f) < 0.05f -> "16:9"
                kotlin.math.abs(r - 9f / 16f) < 0.05f -> "9:16"
                kotlin.math.abs(r - 4f / 3f) < 0.05f -> "4:3"
                kotlin.math.abs(r - 3f / 4f) < 0.05f -> "3:4"
                kotlin.math.abs(r - 3f / 2f) < 0.05f -> "3:2"
                kotlin.math.abs(r - 2f / 3f) < 0.05f -> "2:3"
                else -> "${w}×${h}"
            }
        }
    }
}
