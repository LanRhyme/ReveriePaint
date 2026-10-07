/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.content.Context
import androidx.core.content.edit
import com.reverie.paint.model.ProjectReferences
import com.reverie.paint.model.ReferenceViewState
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Private local sidecars. Project paths are only index keys; no metadata enters the artwork file. */
internal class LocalReferenceStore(private val context: Context) {
    private val index get() = context.getSharedPreferences("reference_projects", 0)
    private val root get() = File(context.filesDir, "project_references")
    private fun prefs(id: String) = context.getSharedPreferences("reference_$id", 0)
    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id)
    }

    fun profile(path: String): String? = index.getString(File(path).absolutePath, null)
        ?.takeIf { runCatching { directory(it) }.isSuccess }

    fun bind(path: String, id: String) {
        directory(id) // Validate before indexing.
        index.edit { putString(File(path).absolutePath, id) }
    }

    fun forgetPath(path: String) = index.edit { remove(File(path).absolutePath) }

    /** IO thread. Count only our private reference copies, including the old global cache. */
    fun cacheBytes(): Long = listOf(root, File(context.filesDir, "ref_images")).sumOf { dir ->
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    /** IO thread under the reference mutex; never traverse artwork or gallery directories. */
    fun clearCache() {
        val ids = (index.all.values.filterIsInstance<String>() +
            root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }).distinct()
        for (dir in listOf(root, File(context.filesDir, "ref_images"))) {
            check(dir.canonicalFile.parentFile == context.filesDir.canonicalFile)
            check(!dir.exists() || dir.deleteRecursively())
        }
        ids.filter { runCatching { directory(it) }.isSuccess }.forEach {
            check(prefs(it).edit().clear().commit())
        }
        check(index.edit().clear().commit())
        context.getSharedPreferences("paint_prefs", 0).edit(commit = true) { putInt("ref_images_count", 0) }
    }

    fun move(source: File, target: File) {
        val entries = index.all
        index.edit {
            for ((path, value) in entries) {
                val moved = ProjectReferences.movedPath(path, source.absolutePath, target.absolutePath) ?: continue
                remove(path)
                putString(moved, value as? String)
            }
        }
    }

    /** IO thread after successful file deletion. Descendant mappings follow deleted folders. */
    fun forget(file: File) {
        val removed = index.all.filterKeys {
            ProjectReferences.movedPath(it, file.absolutePath, file.absolutePath) != null
        }
        index.edit(commit = true) { removed.keys.forEach { remove(it) } }
        removed.values.filterIsInstance<String>().distinct().forEach { releaseUnbound(it) }
    }

    /** IO thread; never remove a profile still used by a saved artwork or autosave alias. */
    fun releaseUnbound(id: String) {
        if (id in index.all.values) return
        directory(id).deleteRecursively()
        prefs(id).edit(commit = true) { clear() }
    }

    fun state(id: String): ReferenceViewState = runCatching {
        val p = prefs(id)
        ProjectReferences.validState(ReferenceViewState(
            p.getBoolean("open", false), p.getBoolean("gray", false), p.getBoolean("flip", false),
            p.getInt("tab", 0), p.getBoolean("collapsed", false), p.getFloat("zoom", 1f),
            p.getFloat("rotation", 0f), p.getFloat("panX", 0f), p.getFloat("panY", 0f),
        ))
    }.getOrDefault(ReferenceViewState())

    fun saveState(id: String, state: ReferenceViewState) = prefs(id).edit {
        putBoolean("open", state.open); putBoolean("gray", state.grayscale); putBoolean("flip", state.flipped)
        putInt("tab", state.tab); putBoolean("collapsed", state.collapsed); putFloat("zoom", state.zoom)
        putFloat("rotation", state.rotation); putFloat("panX", state.panX); putFloat("panY", state.panY)
    }

    fun images(id: String): List<File> {
        val names = JSONArray(prefs(id).getString("images", "[]"))
        require(names.length() <= ProjectReferences.MAX_IMAGES)
        return (0 until names.length()).map {
            val name = names.getString(it)
            require(File(name).extension in setOf("png", "jpg", "webp"))
            require(UUID.fromString(File(name).nameWithoutExtension).toString() + "." + File(name).extension == name)
            File(directory(id), name)
        }
    }

    /** IO thread. Images are immutable; publish the ordered list only once all writes succeeded. */
    fun saveImages(id: String, files: List<File>) {
        require(files.size <= ProjectReferences.MAX_IMAGES)
        require(files.all { it.parentFile == directory(id) && it.isFile })
        require(files.sumOf { it.length() } <= ProjectReferences.MAX_BYTES)
        check(prefs(id).edit().putString("images", JSONArray(files.map { it.name }).toString()).commit())
        // Remove only obsolete files in this profile, never user gallery files or legacy backups.
        val keep = files.map { it.name }.toSet()
        directory(id).listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
    }

    fun newImageFile(id: String): File {
        val dir = directory(id)
        check(dir.isDirectory || dir.mkdirs())
        return File(dir, "${UUID.randomUUID()}.png")
    }
}
