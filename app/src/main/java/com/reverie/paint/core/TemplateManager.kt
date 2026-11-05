/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.reverie.paint.model.CanvasTemplate
import com.reverie.paint.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 模板工程管理器 (负责模板的持久化、元数据管理、导入解析以及基于模板创建工程)。
 */
object TemplateManager {
    private const val META_FILE_NAME = "templates_meta.json"
    const val CATEGORY_ALL = "全部"
    const val CATEGORY_UNCATEGORIZED = "未分类"

    fun getTemplatesDir(context: Context): File {
        val dir = File(context.filesDir, "templates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMetaFile(context: Context): File {
        return File(getTemplatesDir(context), META_FILE_NAME)
    }

    /** 读取所有分类标签与模板列表 */
    fun loadTemplateData(context: Context): Pair<List<String>, List<CanvasTemplate>> {
        val metaFile = getMetaFile(context)
        val defaultCategories = listOf("漫画", "插画", "纸纹", CATEGORY_UNCATEGORIZED)
        if (!metaFile.exists()) {
            return Pair(defaultCategories, emptyList())
        }

        return try {
            val text = metaFile.readText()
            val json = JSONObject(text)
            val categoriesJson = json.optJSONArray("categories")
            val categories = mutableListOf<String>()
            if (categoriesJson != null) {
                for (i in 0 until categoriesJson.length()) {
                    val cat = categoriesJson.optString(i).trim()
                    if (cat.isNotEmpty() && !categories.contains(cat)) {
                        categories.add(cat)
                    }
                }
            }
            if (!categories.contains(CATEGORY_UNCATEGORIZED)) {
                categories.add(CATEGORY_UNCATEGORIZED)
            }

            val templatesJson = json.optJSONArray("templates")
            val templates = mutableListOf<CanvasTemplate>()
            var metaNeedsUpdate = false
            if (templatesJson != null) {
                for (i in 0 until templatesJson.length()) {
                    val obj = templatesJson.optJSONObject(i) ?: continue
                    val filePath = obj.optString("filePath")
                    if (filePath.isNotEmpty() && File(filePath).exists()) {
                        val file = File(filePath)
                        var previewPath = obj.optString("previewPath", "")
                        if (previewPath.isEmpty() || !File(previewPath).exists()) {
                            val candidateThumb = File(getTemplatesDir(context), "${file.nameWithoutExtension}_thumb.png")
                            if (candidateThumb.exists() && candidateThumb.length() > 0) {
                                previewPath = candidateThumb.absolutePath
                                metaNeedsUpdate = true
                            } else if (file.extension.lowercase() == "revp" || file.extension.lowercase() == "kra") {
                                try {
                                    ZipFile(file).use { zf ->
                                        val entry = zf.getEntry("thumbnail.png")
                                            ?: zf.getEntry("preview.png")
                                            ?: zf.getEntry("mergedimage.png")
                                        if (entry != null) {
                                            zf.getInputStream(entry).use { input ->
                                                FileOutputStream(candidateThumb).use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                            if (candidateThumb.exists() && candidateThumb.length() > 0) {
                                                previewPath = candidateThumb.absolutePath
                                                metaNeedsUpdate = true
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("TemplateManager", "Failed to extract thumbnail in loadTemplateData", e)
                                }
                            }
                        }
                        templates.add(
                            CanvasTemplate(
                                id = obj.optString("id", UUID.randomUUID().toString()),
                                name = obj.optString("name", "未命名模板"),
                                category = obj.optString("category", CATEGORY_UNCATEGORIZED),
                                filePath = filePath,
                                previewPath = previewPath,
                                width = obj.optInt("width", 1080),
                                height = obj.optInt("height", 1920),
                                dpi = obj.optInt("dpi", 300),
                                layerCount = obj.optInt("layerCount", 1),
                                description = obj.optString("description", ""),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                isAnimation = obj.optBoolean("isAnimation", false),
                            )
                        )
                    }
                }
            }
            if (metaNeedsUpdate) {
                saveTemplateData(context, categories, templates)
            }
            Pair(categories, templates)
        } catch (e: Exception) {
            android.util.Log.e("TemplateManager", "Failed to parse template metadata", e)
            Pair(defaultCategories, emptyList())
        }
    }

    /** 保存分类标签与模板列表元数据 */
    fun saveTemplateData(context: Context, categories: List<String>, templates: List<CanvasTemplate>) {
        try {
            val json = JSONObject()
            val categoriesJson = JSONArray()
            categories.filter { it != CATEGORY_ALL }.forEach { categoriesJson.put(it) }
            json.put("categories", categoriesJson)

            val templatesJson = JSONArray()
            for (t in templates) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("category", t.category)
                    put("filePath", t.filePath)
                    put("previewPath", t.previewPath)
                    put("width", t.width)
                    put("height", t.height)
                    put("dpi", t.dpi)
                    put("layerCount", t.layerCount)
                    put("description", t.description)
                    put("createdAt", t.createdAt)
                    put("isAnimation", t.isAnimation)
                }
                templatesJson.put(obj)
            }
            json.put("templates", templatesJson)

            val metaFile = getMetaFile(context)
            val tempFile = File(metaFile.parentFile, "$META_FILE_NAME.tmp")
            tempFile.writeText(json.toString(2))
            if (tempFile.exists()) {
                if (metaFile.exists()) metaFile.delete()
                tempFile.renameTo(metaFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("TemplateManager", "Failed to save template metadata", e)
        }
    }

    /** 将当前画布保存为模板 */
    suspend fun saveCurrentAsTemplate(
        context: Context,
        vm: PaintViewModel,
        name: String,
        category: String,
        description: String,
    ): CanvasTemplate? = withContext(Dispatchers.IO) {
        val dir = getTemplatesDir(context)
        val id = UUID.randomUUID().toString()
        val templateFile = File(dir, "$id.revp")
        val thumbFile = File(dir, "${id}_thumb.png")

        // 1. 生成并保存缩略图
        val snapBitmap = vm.displayBitmap
        if (snapBitmap != null && !snapBitmap.isRecycled) {
            try {
                FileOutputStream(thumbFile).use { out ->
                    snapBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } catch (e: Exception) {
                android.util.Log.w("TemplateManager", "Failed to write template thumb", e)
            }
        }

        // 2. 将当前引擎中图层与文档数据保存为 .revp
        val extraJson = JSONObject().apply {
            put("width", vm.docWidth)
            put("height", vm.docHeight)
            put("dpi", vm.docDpi)
            put("name", name)
            put("isTemplate", true)
            put("description", description)
        }.toString()

        val saved = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            vm.runCore(render = false, after = {
                cont.resume(templateFile.exists()) { _, _, _ -> }
            }) {
                ReverieCoreBridge.saveRevp(templateFile.absolutePath, extraJson, ByteArray(0))
            }
        }
        if (!saved || !templateFile.exists()) {
            thumbFile.delete()
            return@withContext null
        }

        // 3. 从新生成的 revp 归档中提取缩略图/预览图
        if (!thumbFile.exists() || thumbFile.length() == 0L) {
            try {
                ZipFile(templateFile).use { zip ->
                    val thumbEntry = zip.getEntry("thumbnail.png")
                        ?: zip.getEntry("preview.png")
                        ?: zip.getEntry("mergedimage.png")
                    if (thumbEntry != null) {
                        zip.getInputStream(thumbEntry).use { input ->
                            FileOutputStream(thumbFile).use { output -> input.copyTo(output) }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TemplateManager", "Failed to extract thumb from template", e)
            }
        }

        val template = CanvasTemplate(
            id = id,
            name = name,
            category = category.ifBlank { CATEGORY_UNCATEGORIZED },
            filePath = templateFile.absolutePath,
            previewPath = if (thumbFile.exists()) thumbFile.absolutePath else "",
            width = vm.docWidth,
            height = vm.docHeight,
            dpi = vm.docDpi,
            layerCount = vm.layers.size.coerceAtLeast(1),
            description = description,
            createdAt = System.currentTimeMillis(),
            isAnimation = vm.anim.enabled,
        )

        val (categories, currentTemplates) = loadTemplateData(context)
        val updatedCategories = if (!categories.contains(template.category) && template.category != CATEGORY_ALL) {
            categories + template.category
        } else {
            categories
        }
        saveTemplateData(context, updatedCategories, listOf(template) + currentTemplates)
        template
    }

    /** 导入外部 .revp / .kra / .psd 文件作为模板 */
    suspend fun importExternalTemplate(
        context: Context,
        uri: Uri,
        targetCategory: String,
        customName: String? = null,
    ): CanvasTemplate? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val originalName = ImageImportHelper.getFileName(context, uri) ?: "imported_template"
            val ext = originalName.substringAfterLast('.', "revp").lowercase()
            val templateName = customName?.takeIf { it.isNotBlank() } ?: originalName.substringBeforeLast('.')

            val dir = getTemplatesDir(context)
            val id = UUID.randomUUID().toString()
            val templateFile = File(dir, "$id.$ext")
            val thumbFile = File(dir, "${id}_thumb.png")

            // 复制文件到 templates 目录
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(templateFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            var w = 1080
            var h = 1920
            var dpi = 300
            var layerCount = 1
            var isAnimation = false

            // 若是 revp 或 kra，尝试提取 meta.json 与缩略图
            if (ext == "revp" || ext == "kra") {
                try {
                    val zip = ZipFile(templateFile)
                    zip.use { zf ->
                        val metaEntry = zf.getEntry("meta.json")
                        if (metaEntry != null) {
                            val metaText = zf.getInputStream(metaEntry).bufferedReader().readText()
                            val json = JSONObject(metaText)
                            w = json.optInt("width", 1080)
                            h = json.optInt("height", 1920)
                            dpi = json.optInt("dpi", 300)
                            layerCount = json.optJSONArray("layers")?.length() ?: 1
                            isAnimation = json.optJSONArray("layers")?.let { arr ->
                                (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("animated", false) == true }
                            } ?: false
                        }
                        val thumbEntry = zf.getEntry("thumbnail.png")
                            ?: zf.getEntry("preview.png")
                            ?: zf.getEntry("mergedimage.png")
                        if (thumbEntry != null) {
                            zf.getInputStream(thumbEntry).use { input ->
                                FileOutputStream(thumbFile).use { output -> input.copyTo(output) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TemplateManager", "Failed to parse zip template meta", e)
                }
            }

            val template = CanvasTemplate(
                id = id,
                name = templateName,
                category = targetCategory.ifBlank { CATEGORY_UNCATEGORIZED },
                filePath = templateFile.absolutePath,
                previewPath = if (thumbFile.exists()) thumbFile.absolutePath else "",
                width = w,
                height = h,
                dpi = dpi,
                layerCount = layerCount,
                description = "",
                createdAt = System.currentTimeMillis(),
                isAnimation = isAnimation,
            )

            val (categories, currentTemplates) = loadTemplateData(context)
            val updatedCategories = if (!categories.contains(template.category) && template.category != CATEGORY_ALL) {
                categories + template.category
            } else {
                categories
            }
            saveTemplateData(context, updatedCategories, listOf(template) + currentTemplates)
            template
        } catch (e: Exception) {
            android.util.Log.e("TemplateManager", "Failed to import external template", e)
            null
        }
    }

    /** 基于模板创建全新项目工程 */
    suspend fun createProjectFromTemplate(
        context: Context,
        vm: PaintViewModel,
        template: CanvasTemplate,
    ): Project? = withContext(Dispatchers.IO) {
        val srcFile = File(template.filePath)
        if (!srcFile.exists()) return@withContext null

        val targetDir = vm.currentFolder?.let { File(it.filePath) }?.takeIf { it.exists() && it.isDirectory }
            ?: vm.projectDir()
        if (!targetDir.exists()) targetDir.mkdirs()

        val timeTag = SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
        var targetName = "${template.name}_$timeTag"
        var targetFile = File(targetDir, "$targetName.${srcFile.extension}")
        var counter = 1
        while (targetFile.exists()) {
            targetName = "${template.name}_${timeTag}_$counter"
            targetFile = File(targetDir, "$targetName.${srcFile.extension}")
            counter++
        }

        try {
            srcFile.copyTo(targetFile, overwrite = false)
        } catch (e: Exception) {
            android.util.Log.e("TemplateManager", "Failed to copy template to project", e)
            return@withContext null
        }

        Project(
            name = targetName,
            width = template.width,
            height = template.height,
            dpi = template.dpi,
            filePath = targetFile.absolutePath,
            previewPath = template.previewPath,
            layerCount = template.layerCount,
            lastModified = targetFile.lastModified(),
            isAnimation = template.isAnimation,
        )
    }

    /** 删除单个模板文件与记录 */
    fun deleteTemplate(context: Context, id: String) {
        val (categories, templates) = loadTemplateData(context)
        val target = templates.find { it.id == id }
        if (target != null) {
            try {
                File(target.filePath).delete()
                if (target.previewPath.isNotEmpty()) File(target.previewPath).delete()
            } catch (_: Exception) {}
            saveTemplateData(context, categories, templates.filter { it.id != id })
        }
    }

    /** 批量删除模板 */
    fun batchDeleteTemplates(context: Context, ids: Set<String>) {
        val (categories, templates) = loadTemplateData(context)
        val remaining = mutableListOf<CanvasTemplate>()
        for (t in templates) {
            if (ids.contains(t.id)) {
                try {
                    File(t.filePath).delete()
                    if (t.previewPath.isNotEmpty()) File(t.previewPath).delete()
                } catch (_: Exception) {}
            } else {
                remaining.add(t)
            }
        }
        saveTemplateData(context, categories, remaining)
    }

    /** 重命名模板 */
    fun renameTemplate(context: Context, id: String, newName: String) {
        val (categories, templates) = loadTemplateData(context)
        val updated = templates.map {
            if (it.id == id) it.copy(name = newName) else it
        }
        saveTemplateData(context, categories, updated)
    }

    /** 修改模板分类标签 */
    fun moveTemplateCategory(context: Context, id: String, newCategory: String) {
        val (categories, templates) = loadTemplateData(context)
        val updated = templates.map {
            if (it.id == id) it.copy(category = newCategory) else it
        }
        val updatedCategories = if (!categories.contains(newCategory) && newCategory != CATEGORY_ALL) {
            categories + newCategory
        } else {
            categories
        }
        saveTemplateData(context, updatedCategories, updated)
    }

    /** 批量修改模板分类标签 */
    fun batchMoveTemplates(context: Context, ids: Set<String>, newCategory: String) {
        val (categories, templates) = loadTemplateData(context)
        val updated = templates.map {
            if (ids.contains(it.id)) it.copy(category = newCategory) else it
        }
        val updatedCategories = if (!categories.contains(newCategory) && newCategory != CATEGORY_ALL) {
            categories + newCategory
        } else {
            categories
        }
        saveTemplateData(context, updatedCategories, updated)
    }

    /** 新增分类标签 */
    fun addCategory(context: Context, categoryName: String) {
        val trimmed = categoryName.trim()
        if (trimmed.isEmpty() || trimmed == CATEGORY_ALL) return
        val (categories, templates) = loadTemplateData(context)
        if (!categories.contains(trimmed)) {
            saveTemplateData(context, categories + trimmed, templates)
        }
    }

    /** 重命名分类标签 */
    fun renameCategory(context: Context, oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == oldName || trimmed == CATEGORY_ALL) return
        val (categories, templates) = loadTemplateData(context)
        val updatedCategories = categories.map { if (it == oldName) trimmed else it }
        val updatedTemplates = templates.map {
            if (it.category == oldName) it.copy(category = trimmed) else it
        }
        saveTemplateData(context, updatedCategories, updatedTemplates)
    }

    /** 删除分类标签 (其下模板转移到未分类) */
    fun deleteCategory(context: Context, categoryName: String) {
        if (categoryName == CATEGORY_ALL || categoryName == CATEGORY_UNCATEGORIZED) return
        val (categories, templates) = loadTemplateData(context)
        val updatedCategories = categories.filter { it != categoryName }
        val updatedTemplates = templates.map {
            if (it.category == categoryName) it.copy(category = CATEGORY_UNCATEGORIZED) else it
        }
        saveTemplateData(context, updatedCategories, updatedTemplates)
    }
}
