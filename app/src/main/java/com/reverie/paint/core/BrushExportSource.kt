/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushDiagnostics
import com.reverie.paint.core.brushstore.BrushImportPipeline
import com.reverie.paint.core.brushstore.BrushStoreBootstrap
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import java.io.File

/**
 * 导出/分享读取字节的唯一入口（设计文档 W-11）：**只读**，绝不写回 `paintoppresets/` 源文件。
 *
 * - v2 管理预设：有效字节 = 快照 + 覆盖（[EffectivePresetBuilder.buildFor]），保证导出与渲染一致；
 * - 其余（旧文件/assets）：kpp 或 myb 原字节。
 *
 * 导出写到 [stage] 临时目录后交给 KritaBundleManager。
 */
internal object BrushExportSource {

    /** 导出内容：扩展名跟预设走（kpp / myb），字节为有效字节 */
    class Exported(val ext: String, val bytes: ByteArray) {
        /** 笔尖文件名以有效字节的 brush_definition 为准；.myb 没有 XML 笔尖 */
        val tipName: String? get() = if (ext == "kpp") KppHelper.extractTipAssetFilename(bytes) else null

        /** 预设引用的纹理图案文件名（只认文件名）；.myb 没有 */
        val patternNames: List<String> get() = if (ext == "kpp") com.reverie.paint.core.brushstore.PlaybackPresetCache.referencedPatternNames(bytes) else emptyList()
    }

    /** [id] 是唯一查找键；名字（条目的显示名）只用来找没有快照的旧文件 / assets 里的原件。 */
    fun effective(id: String, presetDir: File, asset: (String) -> ByteArray? = { null }): Exported? {
        val e = BrushStoreVm.resolveEntry(id) ?: return null
        EffectivePresetBuilder.buildFor(e.id)?.let { return Exported(e.ext, it) }
        BrushDiagnostics.log("export_fallback_raw", "id" to e.id, "name" to e.name)
        for (ext in BrushImportPipeline.PRESET_EXTS) {
            val src = File(presetDir, "${e.name}.$ext")
            if (src.isFile) return Exported(ext, src.readBytes())
            asset("paintoppresets/${e.name}.$ext")?.let { return Exported(ext, it) }
        }
        return null
    }

    fun assetReader(ctx: android.content.Context): (String) -> ByteArray? = { path ->
        try { ctx.assets.open(path).use { it.readBytes() } } catch (_: Exception) { null }
    }

    /** 写入 [stagingDir]/<name>.<ext>，避免同名文件冲突。 */
    fun stage(name: String, exp: Exported, stagingDir: File): File {
        stagingDir.mkdirs()
        val f = File(stagingDir, "$name.${exp.ext}")
        f.writeBytes(exp.bytes)
        BrushDiagnostics.log("export_stage", "name" to name, "ext" to exp.ext, "md5" to BrushDiagnostics.md5(exp.bytes))
        return f
    }

    /** 纹理图案文件：用户 patterns/ → assets/patterns/（后者复制进暂存目录的 patterns/ 子目录）；找不到的跳过并记日志。 */
    fun patternFiles(exp: Exported, stagingDir: File, patternDir: File, asset: (String) -> ByteArray?): List<File> =
        exp.patternNames.mapNotNull { name ->
            val live = File(patternDir, name)
            val f = if (live.isFile) live else asset("patterns/$name")?.let { b ->
                File(stagingDir, "patterns/$name").also { it.parentFile?.mkdirs(); it.writeBytes(b) }
            }
            if (f == null) BrushDiagnostics.log("export_pattern_missing", "name" to name)
            f
        }

    /** 笔尖文件：已暂存 → 用户 brushes/ → assets/brushes/（后者复制进暂存目录）。分享单个与导出整组共用。 */
    fun tipFile(tipName: String, stagingDir: File, brushDir: File, asset: (String) -> ByteArray?): File? {
        val staged = File(stagingDir, tipName)
        if (staged.exists()) return staged
        val live = File(brushDir, tipName)
        if (live.exists()) return live
        val bytes = asset("brushes/$tipName") ?: return null
        staged.parentFile?.mkdirs()
        staged.writeBytes(bytes)
        return staged
    }
}
