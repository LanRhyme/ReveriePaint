/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import com.reverie.paint.model.AutoSaveSnapshot
import com.reverie.paint.model.AutoSaveSnapshotPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object AutoSaveHistoryManager {
    const val DEFAULT_MAX_SNAPSHOTS = AutoSaveSnapshotPolicy.DEFAULT_MAX_SNAPSHOTS
    private const val META_FILE_NAME = "snapshots_meta.json"

    fun getMaxSnapshots(context: Context): Int {
        return context.getSharedPreferences("paint_prefs", Context.MODE_PRIVATE)
            .getInt("autoSaveMaxSnapshots", DEFAULT_MAX_SNAPSHOTS)
            .coerceIn(AutoSaveSnapshotPolicy.MIN_MAX_SNAPSHOTS, AutoSaveSnapshotPolicy.MAX_MAX_SNAPSHOTS)
    }

    fun getHistoryDir(context: Context): File {
        val dir = File(context.filesDir, "autosave_history")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun getSnapshots(context: Context): List<AutoSaveSnapshot> {
        val dir = getHistoryDir(context)
        val metaFile = File(dir, META_FILE_NAME)
        if (!metaFile.exists()) return emptyList()
        val list = mutableListOf<AutoSaveSnapshot>()
        try {
            val jsonStr = metaFile.readText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val fileName = obj.optString("fileName")
                val revpFile = File(dir, fileName)
                if (revpFile.exists() && revpFile.length() > 0) {
                    list.add(
                        AutoSaveSnapshot(
                            id = id,
                            fileName = fileName,
                            displayName = obj.optString("displayName", "未命名作品"),
                            masterPath = obj.optString("masterPath", ""),
                            timestamp = obj.optLong("timestamp", revpFile.lastModified()),
                            strokeCount = obj.optInt("strokeCount", 0),
                            layerCount = obj.optInt("layerCount", 1),
                            fileSize = revpFile.length(),
                            thumbPath = File(dir, "${id}_thumb.png").takeIf { it.exists() }?.absolutePath ?: "",
                            isEmergency = obj.optBoolean("isEmergency", false),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("AutoSaveHistory", "Failed to parse snapshots meta", t)
        }
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun recordSnapshot(
        context: Context,
        sourceRevpFile: File,
        displayName: String,
        masterPath: String,
        strokeCount: Int,
        layerCount: Int,
        isEmergency: Boolean = false,
    ) {
        try {
            if (!sourceRevpFile.exists() || sourceRevpFile.length() == 0L) return
            val currentList = getSnapshots(context)
            val projectKey = if (masterPath.isNotBlank()) masterPath else displayName
            val existingForProject = currentList.filter {
                AutoSaveSnapshotPolicy.projectKey(it) == projectKey
            }

            val now = System.currentTimeMillis()
            if (AutoSaveSnapshotPolicy.shouldSkipRecord(existingForProject, strokeCount, now, isEmergency, layerCount)) {
                return
            }

            val dir = getHistoryDir(context)
            val id = "snap_${now}"
            val targetRevp = File(dir, "$id.revp")
            sourceRevpFile.copyTo(targetRevp, overwrite = true)

            // 提取缩略图
            val thumbFile = File(dir, "${id}_thumb.png")
            try {
                ZipFile(targetRevp).use { zip ->
                    val entry = zip.getEntry("thumbnail.png") ?: zip.getEntry("preview.png")
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            thumbFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}

            val newSnapshot = AutoSaveSnapshot(
                id = id,
                fileName = targetRevp.name,
                displayName = displayName,
                masterPath = masterPath,
                timestamp = now,
                strokeCount = strokeCount,
                layerCount = layerCount,
                fileSize = targetRevp.length(),
                thumbPath = thumbFile.takeIf { it.exists() }?.absolutePath ?: "",
                isEmergency = isEmergency,
            )

            val maxSnapshots = getMaxSnapshots(context)
            val (retained, evicted) = AutoSaveSnapshotPolicy.prune(currentList, newSnapshot, maxSnapshots)

            evicted.forEach { item ->
                File(dir, item.fileName).delete()
                File(dir, "${item.id}_thumb.png").delete()
            }

            saveMeta(dir, retained)
        } catch (t: Throwable) {
            android.util.Log.e("AutoSaveHistory", "Failed to record snapshot", t)
        }
    }

    @Synchronized
    fun pruneToLimit(context: Context, limit: Int) {
        val dir = getHistoryDir(context)
        val currentList = getSnapshots(context).toMutableList()
        val effectiveLimit = limit.coerceIn(AutoSaveSnapshotPolicy.MIN_MAX_SNAPSHOTS, AutoSaveSnapshotPolicy.MAX_MAX_SNAPSHOTS)
        if (currentList.size <= effectiveLimit) return

        val evicted = mutableListOf<AutoSaveSnapshot>()
        while (currentList.size > effectiveLimit) {
            val candidate = currentList.minWithOrNull(
                compareBy<AutoSaveSnapshot> { it.isEmergency }
                    .thenBy { it.timestamp }
            ) ?: currentList.last()
            currentList.remove(candidate)
            evicted.add(candidate)
        }

        evicted.forEach { item ->
            File(dir, item.fileName).delete()
            File(dir, "${item.id}_thumb.png").delete()
        }
        saveMeta(dir, currentList)
    }

    @Synchronized
    fun deleteSnapshot(context: Context, id: String) {
        val dir = getHistoryDir(context)
        val currentList = getSnapshots(context).toMutableList()
        val item = currentList.find { it.id == id } ?: return
        currentList.remove(item)
        File(dir, item.fileName).delete()
        File(dir, "${item.id}_thumb.png").delete()
        saveMeta(dir, currentList)
    }

    @Synchronized
    fun clearSnapshots(context: Context) {
        val dir = getHistoryDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun saveMeta(dir: File, list: List<AutoSaveSnapshot>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("displayName", item.displayName)
                put("masterPath", item.masterPath)
                put("timestamp", item.timestamp)
                put("strokeCount", item.strokeCount)
                put("layerCount", item.layerCount)
                put("isEmergency", item.isEmergency)
            }
            array.put(obj)
        }
        File(dir, META_FILE_NAME).writeText(array.toString())
    }
}
