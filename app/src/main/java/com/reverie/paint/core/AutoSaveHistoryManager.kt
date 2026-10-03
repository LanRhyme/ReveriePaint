/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import com.reverie.paint.model.AutoSaveSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object AutoSaveHistoryManager {
    const val MAX_SNAPSHOTS = 5
    private const val META_FILE_NAME = "snapshots_meta.json"

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
    ) {
        try {
            if (!sourceRevpFile.exists() || sourceRevpFile.length() == 0L) return
            val dir = getHistoryDir(context)
            val id = "snap_${System.currentTimeMillis()}"
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

            val currentList = getSnapshots(context).toMutableList()
            val newSnapshot = AutoSaveSnapshot(
                id = id,
                fileName = targetRevp.name,
                displayName = displayName,
                masterPath = masterPath,
                timestamp = System.currentTimeMillis(),
                strokeCount = strokeCount,
                layerCount = layerCount,
                fileSize = targetRevp.length(),
                thumbPath = thumbFile.takeIf { it.exists() }?.absolutePath ?: "",
            )
            currentList.add(0, newSnapshot)

            // 严格保持最多 5 个快照，超额自动淘汰最旧快照
            while (currentList.size > MAX_SNAPSHOTS) {
                val removed = currentList.removeAt(currentList.lastIndex)
                File(dir, removed.fileName).delete()
                File(dir, "${removed.id}_thumb.png").delete()
            }

            saveMeta(dir, currentList)
        } catch (t: Throwable) {
            android.util.Log.e("AutoSaveHistory", "Failed to record snapshot", t)
        }
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
            }
            array.put(obj)
        }
        File(dir, META_FILE_NAME).writeText(array.toString())
    }
}
