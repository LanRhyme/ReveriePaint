package com.reverie.paint.model

import java.io.File

/**
 * 延时摄影工程存储关联与命名工具
 */
object TimelapseStorage {
    /**
     * 剥离工程名称中的自动保存与抢救标记后缀
     */
    fun cleanProjectName(rawName: String): String {
        return rawName
            .trim()
            .removeSuffix(".autosave")
            .removeSuffix(".emergency")
            .trim()
    }

    /**
     * 计算工程文件相对于 projects/ 根目录的画集前缀键
     * 若位于 projects/ 根目录或无法关联，返回空字符串
     * 若位于子画集（例如 projects/画集A/ 或 projects/画集A/子集/），返回以 "_" 连接的路径键（如 "画集A"）
     */
    fun resolveFolderKey(filesDir: File, filePath: String, masterFilePath: String? = null): String {
        val rootDir = File(filesDir, "projects")
        val effectiveFile = if (!masterFilePath.isNullOrBlank()) {
            File(masterFilePath)
        } else {
            File(filePath)
        }
        val parent = effectiveFile.parentFile ?: return ""
        return if (parent != rootDir && parent.startsWith(rootDir)) {
            parent.relativeTo(rootDir).path.replace(File.separatorChar, '_')
        } else {
            ""
        }
    }

    /**
     * 解析工程对应的延时摄影帧目录名称：
     * 1. 剥离 .autosave 与 .emergency 后缀，确保与作画中录制的真实工程名完全一致
     * 2. 计算相对于 projects/ 的相对路径层级，防止不同画集下的同名工程发生冲突
     * 3. 命名规则：若在画集内为 "<画集路径>__<工程名>"，若在根目录则为 "<工程名>"
     */
    fun resolveTimelapseDirName(
        filesDir: File,
        filePath: String,
        projectName: String? = null,
        masterFilePath: String? = null,
    ): String? {
        val projFile = File(filePath)
        val rawName = projectName?.ifBlank { null } ?: projFile.nameWithoutExtension
        val cleanName = cleanProjectName(rawName)
        if (cleanName.isEmpty()) return null

        val folderKey = resolveFolderKey(filesDir, filePath, masterFilePath)
        return if (folderKey.isNotEmpty()) "${folderKey}__${cleanName}" else cleanName
    }

    /**
     * 获取工程对应的延时摄影帧目录：<filesDir>/timelapse/<dirName>/
     * 具备自动迁移历史目录的能力：
     * 1. 若当前位于画集内且未迁移，历史扁平目录迁移为前缀目录
     * 2. 旧版工程同级 frames 目录迁移
     */
    fun resolveTimelapseFramesDir(
        filesDir: File,
        filePath: String,
        projectName: String? = null,
        masterFilePath: String? = null,
    ): File? {
        val dirName = resolveTimelapseDirName(filesDir, filePath, projectName, masterFilePath) ?: return null
        val timelapseRoot = File(filesDir, "timelapse")
        val newDir = File(timelapseRoot, dirName)

        val projFile = File(filePath)
        val rawName = projectName?.ifBlank { null } ?: projFile.nameWithoutExtension
        val cleanName = cleanProjectName(rawName)

        // 迁移 1: 若当前位于画集内 (dirName != cleanName) 且 newDir 不存在，
        // 且根目录下不存在同名正规工程时，将历史扁平目录就地迁移至前缀目录
        if (dirName != cleanName && !newDir.exists()) {
            val rootDir = File(filesDir, "projects")
            val rootHasSameProject = File(rootDir, "$cleanName.revp").exists() || File(rootDir, "$cleanName.kra").exists()
            if (!rootHasSameProject) {
                val flatDir = File(timelapseRoot, cleanName)
                if (flatDir.exists() && flatDir.isDirectory) {
                    flatDir.renameTo(newDir)
                }
            }
        }

        // 迁移 2: 旧版工程同级 frames/<cleanName> 目录迁移
        val legacyDir = projFile.parentFile?.let { File(it, "frames/$cleanName") }
        if (legacyDir?.exists() == true && legacyDir.isDirectory) {
            if (!timelapseRoot.exists()) timelapseRoot.mkdirs()
            if (!newDir.exists()) {
                legacyDir.renameTo(newDir)
            } else {
                legacyDir.listFiles()?.forEach { f ->
                    val dest = File(newDir, f.name)
                    if (!dest.exists()) f.renameTo(dest) else f.delete()
                }
                legacyDir.delete()
            }
            val legacyParent = projFile.parentFile?.let { File(it, "frames") }
            if (legacyParent?.listFiles()?.isEmpty() == true) {
                legacyParent.delete()
            }
        }

        return newDir
    }
}
