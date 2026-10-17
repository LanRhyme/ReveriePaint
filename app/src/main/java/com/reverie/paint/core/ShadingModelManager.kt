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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 智能阴影辅助 ONNX 神经网络模型管理器。
 * 负责模型的本地存储、官方推荐模型下载流转、自定义模型从系统 SAF 导入、随时卸载与状态查询。
 */
object ShadingModelManager {

    private const val DEFAULT_MODEL_NAME = "depth_anything_v2_small.onnx"
    private const val CUSTOM_MODEL_NAME = "custom_shading.onnx"
    private const val PREFS_NAME = "shading_model_prefs"
    private const val KEY_USER_UNINSTALLED = "user_uninstalled"
    const val DEFAULT_MODEL_SIZE_BYTES = 27_258_801L // 26MB

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
        // 清理过时的旧模型
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

        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists() && custom.length() > 0) {
            modelStatus = ModelStatus.Ready(custom.absolutePath, isCustom = true, fileName = custom.name)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uninstalled = prefs.getBoolean(KEY_USER_UNINSTALLED, false)

        val defaultFile = File(dir, DEFAULT_MODEL_NAME)
        if (uninstalled) {
            if (modelStatus !is ModelStatus.Downloading) {
                modelStatus = ModelStatus.NotDownloaded
            }
            return
        }

        if (defaultFile.exists() && defaultFile.length() > 1024 * 1024) {
            modelStatus = ModelStatus.Ready(
                defaultFile.absolutePath,
                isCustom = false,
                fileName = "Depth Anything V2 (26MB)"
            )
            return
        }

        if (modelStatus !is ModelStatus.Downloading) {
            modelStatus = ModelStatus.NotDownloaded
        }
    }

    /** 获取当前可用模型文件路径 (若无任何就绪模型返回 null) */
    fun getActiveModelFile(context: Context): File? {
        val dir = getModelDir(context)
        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists() && custom.length() > 0) return custom
        val defaultFile = File(dir, DEFAULT_MODEL_NAME)
        if (defaultFile.exists() && defaultFile.length() > 1024 * 1024) return defaultFile
        return null
    }

    /** 卸载模型：删除已下载或导入的模型文件，释放存储空间 */
    fun uninstallModel(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        val dir = getModelDir(context)
        val defaultFile = File(dir, DEFAULT_MODEL_NAME)
        if (defaultFile.exists()) defaultFile.delete()
        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists()) custom.delete()
        val tmpDownload = File(dir, "$DEFAULT_MODEL_NAME.download")
        if (tmpDownload.exists()) tmpDownload.delete()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USER_UNINSTALLED, true).apply()

        modelStatus = ModelStatus.NotDownloaded
    }

    private val DOWNLOAD_MIRRORS = listOf(
        "https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/depth_anything_v2_small.onnx",
        "https://ghfast.top/https://github.com/LanRhyme/ReveriePaint/releases/download/v1.5.0/depth_anything_v2_small.onnx"
    )

    private val okHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 启动下载/解压推荐模型 */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        downloadJob?.cancel()
        val dir = getModelDir(context)
        val targetFile = File(dir, DEFAULT_MODEL_NAME)
        val tempFile = File(dir, "$DEFAULT_MODEL_NAME.download")

        modelStatus = ModelStatus.Downloading(0, 0L, DEFAULT_MODEL_SIZE_BYTES)

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val hasAsset = try {
                    context.assets.list("models")?.contains(DEFAULT_MODEL_NAME) == true
                } catch (_: Exception) {
                    false
                }

                if (hasAsset) {
                    context.assets.open("models/$DEFAULT_MODEL_NAME").use { input ->
                        val total = DEFAULT_MODEL_SIZE_BYTES
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

                    for (url in DOWNLOAD_MIRRORS) {
                        if (!isActive) break
                        try {
                            val request = okhttp3.Request.Builder().url(url).build()
                            val response = okHttpClient.newCall(request).execute()
                            response.use { res ->
                                if (!res.isSuccessful) {
                                    throw IllegalStateException("HTTP ${res.code}")
                                }
                                val body = res.body ?: throw IllegalStateException("Empty response body")
                                val total = body.contentLength().let { if (it > 0) it else DEFAULT_MODEL_SIZE_BYTES }
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
                            if (isActive && tempFile.exists() && tempFile.length() > 1024 * 1024) {
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

                if (tempFile.exists() && tempFile.length() > 1024 * 1024) {
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean(KEY_USER_UNINSTALLED, false).apply()

                        withContext(Dispatchers.Main) {
                            modelStatus = ModelStatus.Ready(
                                modelPath = targetFile.absolutePath,
                                isCustom = false,
                                fileName = "Depth Anything V2 (26MB)"
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
            prefs.edit().putBoolean(KEY_USER_UNINSTALLED, false).apply()

            withContext(Dispatchers.Main) {
                modelStatus = ModelStatus.Ready(targetFile.absolutePath, isCustom = true, fileName = targetFile.name)
            }
            targetFile
        }
    }

    /** 清理自定义模型并恢复默认状态 */
    fun clearCustomModel(context: Context) {
        val dir = getModelDir(context)
        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists()) {
            custom.delete()
        }
        checkStatus(context)
    }

    /** 取消当前正在进行的下载 */
    fun cancelDownload(context: Context? = null) {
        downloadJob?.cancel()
        downloadJob = null
        if (context != null) {
            val dir = getModelDir(context)
            val tempFile = File(dir, "$DEFAULT_MODEL_NAME.download")
            if (tempFile.exists()) tempFile.delete()
        }
        modelStatus = ModelStatus.NotDownloaded
    }
}
