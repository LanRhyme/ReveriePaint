/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.ReverieCoreBridge
import com.reverie.paint.model.RecordingBuffer
import com.reverie.paint.model.RecordingReader
import com.reverie.paint.core.KppHelper
import java.io.File

/**
 * 回放用的预设解析（执行文档 W-12）。
 *
 * 录制：v2 管理的预设在切换时记 `T_PRESET_SELECT_V2`（idx、name、presetId、有效字节 md5），
 * 并把这份有效字节存进 `brush_store/playback_cache/<md5>.kpp`（按 mtime 做 LRU，上限 [MAX_ENTRIES]）。
 *
 * 回放：
 * - 当前有效字节 md5 == 录制时 md5 → [Resolution.EXACT]：只 loadBrushPreset + 橡皮擦标志，**不再按 legacyParams 逐个 setter 补参数**；
 * - md5 不同但缓存里有 → [Resolution.CACHED]：按**录制时的缓存字节**加载（loadBrushPresetFile），不是当前预设；
 * - 缓存也没有 → 先 [rebuildFromSnapshot]：用该笔的快照（原始字节，不含当前覆盖）重建 → [Resolution.REBUILT]，
 *   录制时的自定义值由随后的 CONTEXT_EXT（9 个形状数值）叠上去 = 快照 + 覆盖；
 *   连快照都没有 → [Resolution.MISSING]：沿用当前笔，只记诊断。
 *
 * 旧录制（`T_PRESET_SELECT`，没有 md5）：不重建，MISSING 路径原样（沿用当前笔），保持原行为。
 *
 * 与实时绘制不同之处（REBUILT 路径）：CONTEXT_EXT 只含 9 个形状值，不含笔尖/纹理/动态等覆盖；
 * 快照取自当前的出厂版本；图案取自当前 patterns 目录；笔尖没有缓存。详见《本轮修复说明》。
 */
object PlaybackPresetCache {
    const val DIR = "playback_cache"
    const val MAX_ENTRIES = 128

    enum class Resolution { EXACT, CACHED, REBUILT, MISSING }

    data class Select(val index: Int, val name: String, val presetId: String, val md5: String)

    fun write(b: RecordingBuffer, s: Select) {
        b.u16(if (s.index < 0) 0xFFFF else s.index.coerceIn(0, 0xFFFE))
        b.str(s.name)
        b.str(s.presetId)
        b.str(s.md5)
    }

    fun read(r: RecordingReader): Select {
        val idx = r.u16()
        val name = r.str()
        val id = r.str()
        val md5 = r.str()
        return Select(if (idx == 0xFFFF) -1 else idx, name, id, md5)
    }

    fun cacheDir(storeRoot: File) = File(storeRoot, DIR)

    fun cacheFile(storeRoot: File, md5: String, ext: String = "kpp") = File(cacheDir(storeRoot), "$md5.$ext")

    /** 回放时只知道 md5：kpp 与 myb 两种缓存哪个在就用哪个 */
    fun findCached(storeRoot: File, md5: String): File? =
        BrushImportPipeline.PRESET_EXTS.map { cacheFile(storeRoot, md5, it) }.firstOrNull { it.isFile }

    /**
     * 录制时调用：v2 管理的预设返回 [Select] 并把有效字节放进缓存；否则返回 null（调用方记旧事件）。
     */
    fun recordSelect(index: Int, id: String): Select? {
        val e = BrushStoreBootstrap.managedEntry(id) ?: return null
        val name = e.name
        val root = BrushStoreBootstrap.storeRootDir ?: return null
        val presetDir = BrushStoreBootstrap.presetDir ?: return null
        val eff = File(presetDir, "$name.${e.ext}")
        if (!eff.isFile) return null
        val bytes = eff.readBytes()
        val md5 = BrushDiagnostics.md5(bytes)
        val f = cacheFile(root, md5, e.ext)
        try {
            if (!f.isFile) {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) tmp.delete()
                prune(root)
            } else {
                f.setLastModified(System.currentTimeMillis())
            }
            if (e.ext == "kpp") cachePatterns(root, md5, bytes)
        } catch (t: Throwable) {
            BrushDiagnostics.log("playback_cache_failed", "name" to name, "err" to t.toString())
        }
        BrushDiagnostics.log("playback_record", "name" to name, "id" to e.id, "md5" to md5)
        return Select(index, name, e.id, md5)
    }

    fun resolve(s: Select): Resolution {
        val root = BrushStoreBootstrap.storeRootDir
        if (root != null) restorePatterns(root, s.md5)
        val presetDir = BrushStoreBootstrap.presetDir
        val e = BrushStoreBootstrap.managedEntry(s.presetId)
        if (e != null && presetDir != null) {
            val eff = EffectivePresetBuilder.targetFile(presetDir, e)
            if (eff.isFile && BrushDiagnostics.md5(eff.readBytes()) == s.md5) return Resolution.EXACT
        }
        val cached = root?.let { findCached(it, s.md5) }
        if (cached != null) {
            cached.setLastModified(System.currentTimeMillis())
            return Resolution.CACHED
        }
        return Resolution.MISSING
    }

    /** 重建：没有录制时的字节（缓存被淘汰）时，用该 id 的快照（原始字节，不含当前覆盖）代替。 */
    fun rebuildFromSnapshot(id: String): File? {
        val root = BrushStoreBootstrap.storeRootDir ?: return null
        val snap = PresetSnapshotStore.snapshotFile(id) ?: return null
        val bytes = try { snap.readBytes() } catch (t: Throwable) {
            BrushDiagnostics.log("playback_rebuild_failed", "id" to id, "err" to t.toString())
            return null
        }
        val ext = snap.extension.lowercase()
        if (ext !in BrushImportPipeline.PRESET_EXTS) return null
        val f = File(cacheDir(root), "rebuilt-${BrushDiagnostics.md5(bytes)}.$ext")
        if (!f.isFile || f.length() != bytes.size.toLong()) {
            try { AtomicFiles.write(f, bytes) } catch (t: Throwable) {
                BrushDiagnostics.log("playback_rebuild_failed", "id" to id, "err" to t.toString())
                return null
            }
        }
        return f
    }

    /** LRU：超过上限时按 mtime 删最旧的 */
    fun prune(storeRoot: File, max: Int = MAX_ENTRIES) {
        val files = cacheDir(storeRoot).listFiles { f -> f.isFile && f.extension in BrushImportPipeline.PRESET_EXTS } ?: return
        if (files.size <= max) return
        files.sortedBy { it.lastModified() }.take(files.size - max).forEach {
            it.delete()
            File(it.parentFile, it.nameWithoutExtension + ".patterns").deleteRecursively()
        }
    }

    fun patternCacheDir(storeRoot: File, md5: String) = File(cacheDir(storeRoot), "$md5.patterns")

    fun livePatternDir(storeRoot: File) = ResourceDirs.patterns(storeRoot.parentFile ?: storeRoot)

    /** 从 kpp 字节里取出图案文件名。只认文件名，避免把绝对路径写进缓存。 */
    internal fun referencedPatternNames(bytes: ByteArray): List<String> {
        val xml = KppHelper.readPresetXml(bytes) ?: return emptyList()
        val raw = KppXml.get(xml, "Texture/Pattern/PatternFileName")?.trim().orEmpty()
        return listOf(File(raw).name).filter { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun cachePatterns(storeRoot: File, md5: String, bytes: ByteArray) {
        val names = referencedPatternNames(bytes)
        if (names.isEmpty()) return
        val srcDir = livePatternDir(storeRoot)
        val destDir = patternCacheDir(storeRoot, md5)
        for (name in names) {
            val src = File(srcDir, name)
            if (!src.isFile) continue
            destDir.mkdirs()
            val dest = File(destDir, name)
            if (!dest.isFile) src.copyTo(dest, overwrite = false)
        }
    }

    /** 图案被删掉或换设备后，把缓存里的 png 补回 patterns/。已有文件不覆盖。 */
    fun restorePatterns(storeRoot: File, md5: String) {
        val srcDir = patternCacheDir(storeRoot, md5)
        if (!srcDir.isDirectory) return
        val rStore = BrushStoreBootstrap.resourceStore ?: ResourceStore(
            storeRoot,
            ResourceDirs.tips(storeRoot.parentFile ?: storeRoot),
            ResourceDirs.patterns(storeRoot.parentFile ?: storeRoot),
        )
        var registeredAny = false
        srcDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            rStore.register(f.readBytes(), ResourceStore.Kind.PATTERN, f.name)
            registeredAny = true
        }
        if (registeredAny) {
            val dir = rStore.dirFor(ResourceStore.Kind.PATTERN)
            runCatching { BrushApply.engine.loadPatternResources(dir.absolutePath) }
        }
    }
}
