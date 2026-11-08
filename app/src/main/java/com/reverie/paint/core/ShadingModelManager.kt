/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reverie.paint.model.ModelStatus
import com.reverie.paint.model.ShadingModelPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 智能阴影辅助 ONNX 神经网络模型管理器。
 * 负责模型的本地存储、预设模型 (AnimeNormal / Depth Anything V2) 下载流转、
 * 自定义模型从系统 SAF 导入、随时卸载与状态查询。
 */
object ShadingModelManager {

    private const val CUSTOM_MODEL_NAME = "custom_shading.onnx"
    private const val PREFS_NAME = "shading_model_prefs"
    private const val KEY_SELECTED_PRESET = "selected_preset"
    private const val KEY_USE_CUSTOM = "use_custom"
    private const val MIN_PRESET_SIZE_BYTES = 30 * 1024 * 1024L // 30MB (正常模型 ~35.6MB, 过滤 27MB 坏模型)

    var currentPreset: ShadingModelPreset by mutableStateOf(ShadingModelPreset.DEPTH_ANYTHING)
        private set

    var isCustomActive: Boolean by mutableStateOf(false)
        private set

    var modelStatus: ModelStatus by mutableStateOf(ModelStatus.NotDownloaded)
        private set

    private var downloadJob: Job? = null

    private fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, "models/shading")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 检查并刷新当前模型就绪状态 */
    fun checkStatus(context: Context) {
        val dir = getModelDir(context)
        // 清理过时的旧实验模型与失效的 PBR 粗糙度模型
        val oldV1 = File(dir, "anime_normal_v1.onnx")
        if (oldV1.exists()) oldV1.delete()
        val oldV2 = File(dir, "anime_normal_v2.onnx")
        if (oldV2.exists()) oldV2.delete()
        val old7mb = File(dir, "anime_normal_7mb.onnx")
        if (old7mb.exists()) old7mb.delete()
        val oldSprite = File(dir, "sprite2normal.onnx")
        if (oldSprite.exists()) oldSprite.delete()
        val oldSpriteData = File(dir, "sprite2normal.onnx.data")
        if (oldSpriteData.exists()) oldSpriteData.delete()
        val oldPbr = File(dir, "anime_normal_pbr.onnx")
        if (oldPbr.exists()) oldPbr.delete()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPresetId = prefs.getString(KEY_SELECTED_PRESET, ShadingModelPreset.DEPTH_ANYTHING.id)
            ?: ShadingModelPreset.DEPTH_ANYTHING.id
        // 自动将旧的 anime_normal 升级为 Depth Anything V2 空间几何模型
        currentPreset = if (savedPresetId == "anime_normal") {
            prefs.edit().putString(KEY_SELECTED_PRESET, ShadingModelPreset.DEPTH_ANYTHING.id).apply()
            ShadingModelPreset.DEPTH_ANYTHING
        } else {
            ShadingModelPreset.fromId(savedPresetId)
        }
        isCustomActive = prefs.getBoolean(KEY_USE_CUSTOM, false)

        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (isCustomActive && custom.exists() && custom.length() > 0) {
            modelStatus = ModelStatus.Ready(custom.absolutePath, isCustom = true, fileName = custom.name, preset = null)
            return
        }

        val targetFile = File(dir, currentPreset.fileName)
        // 自动清除过时/损坏的非标准模型 (如早先上传的 27MB ConvInteger 异常模型)
        if (targetFile.exists() && targetFile.length() < MIN_PRESET_SIZE_BYTES) {
            targetFile.delete()
        }
        if (targetFile.exists() && targetFile.length() >= MIN_PRESET_SIZE_BYTES) {
            val displayName = "Depth Anything V2 (34MB)"
            modelStatus = ModelStatus.Ready(
                modelPath = targetFile.absolutePath,
                isCustom = false,
                fileName = displayName,
                preset = currentPreset
            )
            return
        }

        if (modelStatus !is ModelStatus.Downloading) {
            modelStatus = ModelStatus.NotDownloaded
        }
    }

    /** 切换选中的预设模型 */
    fun selectPreset(preset: ShadingModelPreset, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_PRESET, preset.id)
            .putBoolean(KEY_USE_CUSTOM, false)
            .apply()
        currentPreset = preset
        isCustomActive = false
        checkStatus(context)
    }

    /** 检查指定预设是否已下载在本地 */
    fun isPresetDownloaded(preset: ShadingModelPreset, context: Context): Boolean {
        val dir = getModelDir(context)
        val file = File(dir, preset.fileName)
        if (file.exists() && file.length() < MIN_PRESET_SIZE_BYTES) {
            file.delete()
            return false
        }
        return file.exists() && file.length() >= MIN_PRESET_SIZE_BYTES
    }

    /** 检查是否存在已导入的自定义模型 */
    fun hasCustomModel(context: Context): Boolean {
        val dir = getModelDir(context)
        val custom = File(dir, CUSTOM_MODEL_NAME)
        return custom.exists() && custom.length() > 0
    }

    /** 切换为已导入的自定义模型 */
    fun selectCustomModel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_CUSTOM, true).apply()
        isCustomActive = true
        checkStatus(context)
    }

    /** 获取当前可用模型文件路径 (若无任何就绪模型返回 null) */
    fun getActiveModelFile(context: Context): File? {
        val dir = getModelDir(context)
        if (isCustomActive) {
            val custom = File(dir, CUSTOM_MODEL_NAME)
            if (custom.exists() && custom.length() > 0) return custom
        }
        val targetFile = File(dir, currentPreset.fileName)
        if (targetFile.exists() && targetFile.length() < MIN_PRESET_SIZE_BYTES) {
            targetFile.delete()
            return null
        }
        if (targetFile.exists() && targetFile.length() >= MIN_PRESET_SIZE_BYTES) return targetFile
        return null
    }

    /** 卸载当前选中的模型文件，释放存储空间 */
    fun uninstallModel(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        val dir = getModelDir(context)

        if (isCustomActive) {
            val custom = File(dir, CUSTOM_MODEL_NAME)
            if (custom.exists()) custom.delete()
            isCustomActive = false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_USE_CUSTOM, false).apply()
        } else {
            val targetFile = File(dir, currentPreset.fileName)
            if (targetFile.exists()) targetFile.delete()
            val tmpDownload = File(dir, "${currentPreset.fileName}.download")
            if (tmpDownload.exists()) tmpDownload.delete()
        }

        checkStatus(context)
    }

    private fun getMirrorsForPreset(preset: ShadingModelPreset): List<String> = listOf(
        "https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/${preset.fileName}",
        "https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/depth_anything_v2_small_clean.onnx",
        "https://ghfast.top/https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/${preset.fileName}",
        "https://ghproxy.net/https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/${preset.fileName}"
    )

    private val okHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 启动下载选中的预设模型 */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        preset: ShadingModelPreset = currentPreset,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        downloadJob?.cancel()
        currentPreset = preset
        val dir = getModelDir(context)
        val targetFile = File(dir, preset.fileName)
        val tempFile = File(dir, "${preset.fileName}.download")
        val expectedSize = preset.defaultSizeBytes

        modelStatus = ModelStatus.Downloading(0, 0L, expectedSize)

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val hasAsset = try {
                    context.assets.list("models")?.contains(preset.fileName) == true
                } catch (_: Exception) {
                    false
                }

                if (hasAsset) {
                    context.assets.open("models/${preset.fileName}").use { input ->
                        val total = expectedSize
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var readTotal = 0L
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (!isActive) break
                                output.write(buffer, 0, bytesRead)
                                readTotal += bytesRead
                                val progress = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                                withContext(Dispatchers.Main) {
                                    modelStatus = ModelStatus.Downloading(progress, readTotal, total)
                                }
                            }
                            output.flush()
                        }
                    }
                } else {
                    var downloadedOk = false
                    var lastException: Exception? = null
                    val mirrors = getMirrorsForPreset(preset)

                    for (url in mirrors) {
                        if (!isActive) break
                        try {
                            val request = okhttp3.Request.Builder().url(url).build()
                            val response = okHttpClient.newCall(request).execute()
                            response.use { res ->
                                if (!res.isSuccessful) {
                                    throw IllegalStateException("HTTP ${res.code}")
                                }
                                val body = res.body ?: throw IllegalStateException("Empty response body")
                                val total = body.contentLength().let { if (it > 0) it else expectedSize }
                                body.byteStream().use { input ->
                                    FileOutputStream(tempFile).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var readTotal = 0L
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            if (!isActive) break
                                            output.write(buffer, 0, bytesRead)
                                            readTotal += bytesRead
                                            val progress = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                                            withContext(Dispatchers.Main) {
                                                modelStatus = ModelStatus.Downloading(progress, readTotal, total)
                                            }
                                        }
                                        output.flush()
                                    }
                                }
                            }
                            if (isActive && tempFile.exists() && tempFile.length() >= MIN_PRESET_SIZE_BYTES) {
                                downloadedOk = true
                                break
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            lastException = e
                            if (tempFile.exists()) tempFile.delete()
                        }
                    }

                    if (!downloadedOk && isActive) {
                        throw lastException ?: IllegalStateException("All download mirrors failed")
                    }
                }

                if (!isActive) {
                    if (tempFile.exists()) tempFile.delete()
                    withContext(Dispatchers.Main) {
                        modelStatus = ModelStatus.NotDownloaded
                    }
                    return@launch
                }

                if (tempFile.exists() && tempFile.length() >= MIN_PRESET_SIZE_BYTES) {
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString(KEY_SELECTED_PRESET, preset.id)
                            .putBoolean(KEY_USE_CUSTOM, false)
                            .apply()
                        currentPreset = preset
                        isCustomActive = false

                        val displayName = "Depth Anything V2 (34MB)"

                        withContext(Dispatchers.Main) {
                            modelStatus = ModelStatus.Ready(
                                modelPath = targetFile.absolutePath,
                                isCustom = false,
                                fileName = displayName,
                                preset = preset
                            )
                            onSuccess()
                        }
                    } else {
                        throw IllegalStateException("Failed to move completed download file")
                    }
                } else {
                    throw IllegalStateException("Downloaded model file is corrupted or empty")
                }
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                if (e is kotlinx.coroutines.CancellationException) {
                    withContext(Dispatchers.Main) {
                        modelStatus = ModelStatus.NotDownloaded
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        modelStatus = ModelStatus.Error(e.message ?: "Download failed")
                        onError(e.message ?: "Download failed")
                    }
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    /** 从本地存储 (SAF Uri) 导入自定义 .onnx 模型 */
    suspend fun importCustomModel(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = getModelDir(context)
            val targetFile = File(dir, CUSTOM_MODEL_NAME)
            val tmpFile = File(dir, "$CUSTOM_MODEL_NAME.tmp")

            val cr = context.contentResolver
            val inputStream: InputStream = cr.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open content URI: $uri")

            inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (!tmpFile.exists() || tmpFile.length() <= 0) {
                throw IllegalStateException("Imported file is empty")
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to save imported model")
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_USE_CUSTOM, true).apply()
            isCustomActive = true

            withContext(Dispatchers.Main) {
                modelStatus = ModelStatus.Ready(
                    targetFile.absolutePath,
                    isCustom = true,
                    fileName = targetFile.name,
                    preset = null
                )
            }
            targetFile
        }
    }

    /** 清理自定义模型并恢复预设状态 */
    fun clearCustomModel(context: Context) {
        val dir = getModelDir(context)
        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists()) {
            custom.delete()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_CUSTOM, false).apply()
        isCustomActive = false
        checkStatus(context)
    }

    /** 取消当前正在进行的下载 */
    fun cancelDownload(context: Context? = null) {
        downloadJob?.cancel()
        downloadJob = null
        if (context != null) {
            val dir = getModelDir(context)
            val tempFile = File(dir, "${currentPreset.fileName}.download")
            if (tempFile.exists()) tempFile.delete()
        }
        modelStatus = ModelStatus.NotDownloaded
    }
}
