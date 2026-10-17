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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 智能阴影辅助 ONNX 神经网络模型管理器。
 * 负责模型的本地存储、官方推荐模型下载流转、自定义模型从系统 SAF 导入与状态查询。
 */
object ShadingModelManager {

    private const val DEFAULT_MODEL_NAME = "depth_anything_v2_small.onnx"
    private const val CUSTOM_MODEL_NAME = "custom_shading.onnx"

    var modelStatus: ModelStatus by mutableStateOf(ModelStatus.NotDownloaded)
        private set

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

        // 检查并解压 assets 内置的 SOTA 深度神经网络模型 (Depth Anything V2 26MB)
        val defaultFile = File(dir, DEFAULT_MODEL_NAME)
        if (!defaultFile.exists() || defaultFile.length() < 1024 * 1024) {
            try {
                context.assets.open("models/$DEFAULT_MODEL_NAME").use { input ->
                    FileOutputStream(defaultFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            } catch (_: Exception) {}
        }

        if (defaultFile.exists() && defaultFile.length() > 0) {
            modelStatus = ModelStatus.Ready(defaultFile.absolutePath, isCustom = false, fileName = "Depth Anything V2 (26MB)")
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
        if (defaultFile.exists() && defaultFile.length() > 0) return defaultFile
        return null
    }

    /** 启动在线下载推荐模型 */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        // 当前默认推荐 100% 纯净零延迟的内置 2.5D 几何光影引擎，或通过下方直接导入本地 ONNX 模型
        onError("请直接导入本地 .onnx 深度/法线模型")
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
        modelStatus = ModelStatus.NotDownloaded
    }
}
