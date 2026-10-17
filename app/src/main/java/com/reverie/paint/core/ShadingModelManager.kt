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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 智能阴影辅助 ONNX 神经网络模型管理器。
 * 负责模型的本地存储、官方推荐模型下载流转、自定义模型从系统 SAF 导入与状态查询。
 */
object ShadingModelManager {

    private const val DEFAULT_MODEL_NAME = "anime_normal_v1.onnx"
    private const val CUSTOM_MODEL_NAME = "custom_shading.onnx"

    /** 官方推荐模型备选下载节点 (HuggingFace CDN / 国内镜像) */
    private const val PRIMARY_MODEL_URL =
        "https://hf-mirror.com/longtimedevs/sprite2normal/resolve/main/sprite2normal.onnx"
    private const val MIRROR_MODEL_URL =
        "https://huggingface.co/longtimedevs/sprite2normal/resolve/main/sprite2normal.onnx"

    var modelStatus: ModelStatus by mutableStateOf(ModelStatus.NotDownloaded)
        private set

    private var downloadJob: Job? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

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
        val custom = File(dir, CUSTOM_MODEL_NAME)
        if (custom.exists() && custom.length() > 0) {
            modelStatus = ModelStatus.Ready(custom.absolutePath, isCustom = true, fileName = custom.name)
            return
        }

        val defaultFile = File(dir, DEFAULT_MODEL_NAME)
        if (defaultFile.exists() && defaultFile.length() > 0) {
            modelStatus = ModelStatus.Ready(defaultFile.absolutePath, isCustom = false, fileName = defaultFile.name)
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
        val def = File(dir, DEFAULT_MODEL_NAME)
        if (def.exists() && def.length() > 0) return def
        return null
    }

    /** 启动在线下载推荐模型 */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (modelStatus is ModelStatus.Downloading) return

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            val dir = getModelDir(context)
            val targetFile = File(dir, DEFAULT_MODEL_NAME)
            val tmpFile = File(dir, "$DEFAULT_MODEL_NAME.tmp")

            withContext(Dispatchers.Main) {
                modelStatus = ModelStatus.Downloading(0, 0L, 0L)
            }

            var downloadSuccess = false
            var lastError = ""

            val urls = listOf(PRIMARY_MODEL_URL, MIRROR_MODEL_URL)
            for (url in urls) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android) ReveriePaint/1.5.0")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        continue
                    }
                    val body = response.body ?: continue
                    val contentLength = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastPercent = 0

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                val percent = if (contentLength > 0) {
                                    ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) {
                                        modelStatus = ModelStatus.Downloading(percent, totalRead, contentLength)
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        if (targetFile.exists()) targetFile.delete()
                        tmpFile.renameTo(targetFile)
                        downloadSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastError = e.message ?: e.toString()
                } finally {
                    if (tmpFile.exists() && !downloadSuccess) {
                        tmpFile.delete()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (downloadSuccess && targetFile.exists()) {
                    modelStatus = ModelStatus.Ready(targetFile.absolutePath, isCustom = false, fileName = targetFile.name)
                    onSuccess()
                } else {
                    modelStatus = ModelStatus.Error(lastError.ifEmpty { "Download failed" })
                    onError(lastError)
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
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        modelStatus = ModelStatus.NotDownloaded
    }
}
