/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.io.File

val PROJECT_ARTWORK_EXTENSIONS: Set<String> = setOf("revp", "kra", "psd", "png")

enum class ProjectRenameError {
    BLANK,
    INVALID_CHARS,
    ALREADY_EXISTS,
    SOURCE_MISSING,
    IO_FAILED,
}

sealed interface ProjectRenameResult {
    data object Unchanged : ProjectRenameResult
    data class Success(val oldFile: File, val newFile: File) : ProjectRenameResult
    data class Failure(val error: ProjectRenameError) : ProjectRenameResult
}

private val projectFileOpsLock = Any()

fun validateProjectName(rawName: String): ProjectRenameError? {
    val trimmed = rawName.trim()
    if (trimmed.isEmpty()) return ProjectRenameError.BLANK
    if (trimmed == "." || trimmed == ".." || trimmed.any { it == '/' || it == '\\' || it == '\u0000' }) {
        return ProjectRenameError.INVALID_CHARS
    }
    return null
}

fun projectFileSuffix(file: File): String {
    val ext = file.extension
    if (ext.isEmpty()) return ""
    val stem = file.nameWithoutExtension
    return when {
        stem.endsWith(".autosave", ignoreCase = true) -> ".autosave.$ext"
        stem.endsWith(".emergency", ignoreCase = true) -> ".emergency.$ext"
        else -> ".$ext"
    }
}

fun projectDisplayName(file: File, isFolder: Boolean): String {
    if (isFolder) return file.name
    val stem = file.nameWithoutExtension
    return when {
        stem.endsWith(".autosave", ignoreCase = true) -> stem.dropLast(".autosave".length)
        stem.endsWith(".emergency", ignoreCase = true) -> stem.dropLast(".emergency".length)
        else -> stem
    }
}

fun resolveRenameTarget(source: File, isFolder: Boolean, newName: String): File? {
    if (validateProjectName(newName) != null) return null
    val parent = source.parentFile ?: return null
    val trimmed = newName.trim()
    return if (isFolder) {
        File(parent, trimmed)
    } else {
        File(parent, "$trimmed${projectFileSuffix(source)}")
    }
}

fun hasArtworkNameConflictInDir(
    dir: File,
    targetDisplayName: String,
    ignoreFile: File? = null,
): Boolean {
    if (!dir.isDirectory) return false
    val ignorePath = ignoreFile?.absolutePath
    return dir.listFiles().orEmpty().any { entry ->
        entry.isFile &&
            (ignorePath == null || entry.absolutePath != ignorePath) &&
            entry.extension.lowercase() in PROJECT_ARTWORK_EXTENSIONS &&
            projectDisplayName(entry, isFolder = false) == targetDisplayName
    }
}

fun checkProjectRenameError(
    source: File,
    isFolder: Boolean,
    newName: String,
    extraConflictDirs: List<File> = emptyList(),
    extraConflictNames: Set<String> = emptySet(),
): ProjectRenameError? {
    validateProjectName(newName)?.let { return it }
    if (!source.exists()) return ProjectRenameError.SOURCE_MISSING
    val trimmed = newName.trim()
    val target = resolveRenameTarget(source, isFolder, trimmed) ?: return ProjectRenameError.INVALID_CHARS
    if (target.absolutePath == source.absolutePath) return null
    if (target.exists()) return ProjectRenameError.ALREADY_EXISTS
    val parent = source.parentFile ?: return ProjectRenameError.IO_FAILED
    if (isFolder) {
        if (parent.listFiles().orEmpty().any { it.absolutePath != source.absolutePath && it.name == trimmed }) {
            return ProjectRenameError.ALREADY_EXISTS
        }
    } else {
        if (hasArtworkNameConflictInDir(parent, trimmed, ignoreFile = source)) {
            return ProjectRenameError.ALREADY_EXISTS
        }
        if (extraConflictDirs.any { hasArtworkNameConflictInDir(it, trimmed, ignoreFile = source) }) {
            return ProjectRenameError.ALREADY_EXISTS
        }
        if (trimmed in extraConflictNames) {
            return ProjectRenameError.ALREADY_EXISTS
        }
    }
    return null
}

fun renameProjectFileSafely(
    source: File,
    isFolder: Boolean,
    newName: String,
    extraConflictDirs: List<File> = emptyList(),
    extraConflictNames: Set<String> = emptySet(),
): ProjectRenameResult {
    synchronized(projectFileOpsLock) {
        checkProjectRenameError(
            source = source,
            isFolder = isFolder,
            newName = newName,
            extraConflictDirs = extraConflictDirs,
            extraConflictNames = extraConflictNames,
        )?.let { return ProjectRenameResult.Failure(it) }

        val target = resolveRenameTarget(source, isFolder, newName.trim())
            ?: return ProjectRenameResult.Failure(ProjectRenameError.INVALID_CHARS)
        if (target.absolutePath == source.absolutePath) return ProjectRenameResult.Unchanged
        // Hard guard against race or OS-level overwrite semantics (POSIX rename replaces existing files).
        if (target.exists()) return ProjectRenameResult.Failure(ProjectRenameError.ALREADY_EXISTS)
        return if (source.renameTo(target)) {
            ProjectRenameResult.Success(source, target)
        } else {
            ProjectRenameResult.Failure(ProjectRenameError.IO_FAILED)
        }
    }
}

fun resolveUniqueMoveDestination(srcFile: File, destDir: File): File? {
    synchronized(projectFileOpsLock) {
        if (srcFile.parentFile?.canonicalFile == destDir.canonicalFile) return null
        val baseName = projectDisplayName(srcFile, isFolder = false)
        val suffix = projectFileSuffix(srcFile)
        val direct = File(destDir, srcFile.name)
        if (!direct.exists() && !hasArtworkNameConflictInDir(destDir, baseName)) {
            return direct
        }
        var counter = 1
        while (true) {
            val candidateName = "$baseName ($counter)"
            val candidate = File(destDir, "$candidateName$suffix")
            if (!candidate.exists() && !hasArtworkNameConflictInDir(destDir, candidateName)) {
                return candidate
            }
            counter++
        }
    }
}
