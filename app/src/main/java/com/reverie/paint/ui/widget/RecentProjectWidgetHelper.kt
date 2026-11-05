/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.appwidget.updateAll
import com.reverie.paint.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 最近画作展示数据封装
 */
data class RecentProjectDisplayData(
    val name: String,
    val filePath: String,
    val width: Int,
    val height: Int,
    val elapsedSeconds: Long,
    val strokeCount: Int,
    val lastModified: Long,
    val thumbnailBitmap: Bitmap?,
)

/**
 * 最近画作桌面小组件数据辅助与广播刷新类
 */
object RecentProjectWidgetHelper {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 读取并生成最新创作项目的展示数据
     */
    fun getLatestProjectDisplayData(context: Context): RecentProjectDisplayData? {
        val rootDir = File(context.filesDir, "projects")
        val candidateFiles = mutableListOf<File>()

        if (rootDir.exists()) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension.lowercase() in listOf("revp", "kra", "png")) {
                    candidateFiles.add(file)
                } else if (file.isDirectory) {
                    file.listFiles { f -> f.isFile && f.extension.lowercase() in listOf("revp", "kra", "png") }?.let {
                        candidateFiles.addAll(it)
                    }
                }
            }
        }

        val autoDir = File(context.filesDir, "autosave")
        if (autoDir.exists()) {
            autoDir.listFiles { f -> f.isFile && f.extension.lowercase() in listOf("revp", "kra") }?.let {
                candidateFiles.addAll(it)
            }
        }

        val latestFile = candidateFiles.maxByOrNull { it.lastModified() } ?: return null
        val project = getProjectFromFile(context, latestFile) ?: return null

        val thumbBitmap = if (project.previewPath.isNotBlank()) {
            decodeSampledBitmap(File(project.previewPath), maxDim = 400)
        } else null

        return RecentProjectDisplayData(
            name = project.name,
            filePath = project.filePath,
            width = project.width,
            height = project.height,
            elapsedSeconds = project.elapsedSeconds,
            strokeCount = project.strokeCount,
            lastModified = project.lastModified,
            thumbnailBitmap = thumbBitmap,
        )
    }

    /**
     * 将工程文件解析为标准的 Project 领域模型
     */
    fun getProjectFromFile(context: Context, f: File): Project? {
        if (!f.exists()) return null
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
                ZipFile(f).use { zip ->
                    val metaEntry = zip.getEntry("meta.json")
                    if (metaEntry != null) {
                        val text = zip.getInputStream(metaEntry).bufferedReader().use { it.readText() }
                        val json = JSONObject(text)
                        w = json.optInt("width", 1080)
                        h = json.optInt("height", 1920)
                        dpi = json.optInt("dpi", 300)
                        strokes = json.optInt("strokeCount", 0)
                        elapsed = json.optLong("elapsedSeconds", 0L)
                        colorModeStr = json.optString("colorMode", "RGB 8位")
                        layerCount = json.optJSONArray("layers")?.length() ?: 1
                        selectedLayerIndex = json.optInt("selectedLayerIndex", -1)
                        masterPath = json.optString("masterFilePath", "")
                        isAnimation = json.optJSONArray("layers")?.let { arr ->
                            (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("animated", false) == true }
                        } ?: false
                    }
                    val prevEntry = zip.getEntry("thumbnail.png") ?: zip.getEntry("preview.png")
                    if (prevEntry != null) {
                        val cacheThumb = File(context.cacheDir, "${f.nameWithoutExtension}_thumb.png")
                        if (!cacheThumb.exists() || cacheThumb.lastModified() < f.lastModified()) {
                            zip.getInputStream(prevEntry).use { input ->
                                cacheThumb.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        previewPath = cacheThumb.absolutePath
                    }
                    hasRec = zip.getEntry("recording") != null
                }
            } catch (_: Exception) {}
        } else if (ext == "png") {
            previewPath = f.absolutePath
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    w = opts.outWidth
                    h = opts.outHeight
                }
            } catch (_: Exception) {}
        }

        val isAutoSaveFile = f.name.contains(".autosave")
        val displayName = if (f.nameWithoutExtension.endsWith(".autosave")) {
            f.nameWithoutExtension.removeSuffix(".autosave")
        } else {
            f.nameWithoutExtension
        }

        return Project(
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
        )
    }

    /**
     * 安全解码并降采样图片以适配桌面小组件 Binder 传输限制
     */
    fun decodeSampledBitmap(file: File, maxDim: Int = 400): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            val maxSide = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxSide / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            if (sampled.width > maxDim || sampled.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(sampled.width, sampled.height)
                val targetW = (sampled.width * scale).toInt().coerceAtLeast(1)
                val targetH = (sampled.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
            } else {
                sampled
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 触发全量最近作品小组件刷新
     */
    fun notifyAllWidgets(context: Context) {
        val appCtx = context.applicationContext ?: context
        scope.launch {
            try {
                RecentProjectDashboardWidget().updateAll(appCtx)
                RecentProjectCompactWidget().updateAll(appCtx)
            } catch (_: Throwable) {}
        }
    }
}
