/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import java.io.File

/**
 * 能力门控（执行文档 W-16）：按 paintop / 笔尖类型判断工作台控件是否作用于当前笔刷。
 *
 * 适用性本身定义在 [PresetPropertyMap]（每个 ControlSpec 的 applicable）；这里只负责
 * 从有效字节得到 [PresetContext]、给出不适用的原因，并按文件 mtime/长度缓存，供 UI 每次重组时廉价查询。
 */
object BrushApplicability {

    enum class Reason {
        /** .myb（MyPaint）没有 Krita XML */
        MYPAINT,
        /** 只对涂抹引擎（colorsmudge）有效 */
        COLORSMUDGE_ONLY,
        /** 预设没有 brush_definition（非像素笔刷引擎） */
        NO_BRUSH_DEF,
        /** 图片笔尖：只作用于参数化（auto）笔尖 */
        AUTO_TIP_ONLY,
    }

    private val SMUDGE_IDS = setOf(
        ControlId.COLOR_RATE, ControlId.SMUDGE_LENGTH, ControlId.SMUDGE_MODE,
        ControlId.CURVE_SMUDGE_RATE, ControlId.CURVE_COLOR_RATE,
    )

    fun reason(id: ControlId, ctx: PresetContext): Reason? {
        if (PresetPropertyMap.spec(id).applicable(ctx)) return null
        return when {
            ctx.isMyPaint -> Reason.MYPAINT
            id in SMUDGE_IDS -> Reason.COLORSMUDGE_ONLY
            !ctx.hasBrushDef -> Reason.NO_BRUSH_DEF
            else -> Reason.AUTO_TIP_ONLY
        }
    }

    /** 所有不适用的控件及原因 */
    fun inapplicable(ctx: PresetContext): Map<ControlId, Reason> =
        ControlId.entries.mapNotNull { id -> reason(id, ctx)?.let { id to it } }.toMap()

    fun contextOf(bytes: ByteArray, ext: String = "kpp"): PresetContext? {
        if (ext.equals("myb", ignoreCase = true)) return PresetContext.MYPAINT
        val xml = KppHelper.readPresetXml(bytes, stripEmbeddedResources = true) ?: return null
        return PresetContext.of(xml)
    }

    private data class Cached(val mtime: Long, val len: Long, val result: Map<ControlId, Reason>)

    private val cache = HashMap<String, Cached>()

    /**
     * 当前笔刷（按 id）的不适用控件。v2 未激活或找不到有效文件时返回空表（不门控，保持旧 UI）。
     */
    @Synchronized
    fun forId(id: String): Map<ControlId, Reason> {
        val dir = BrushStoreBootstrap.presetDir ?: return emptyMap()
        val e = BrushIdentity.entry(id) ?: return emptyMap()
        if (e.ext.equals("myb", ignoreCase = true)) return inapplicable(PresetContext.MYPAINT)
        val f = EffectivePresetBuilder.targetFile(dir, e)
        if (!f.isFile) return emptyMap()
        val key = f.absolutePath
        val c = cache[key]
        if (c != null && c.mtime == f.lastModified() && c.len == f.length()) return c.result
        val ctx = contextOf(f.readBytes()) ?: return emptyMap()
        val r = inapplicable(ctx)
        cache[key] = Cached(f.lastModified(), f.length(), r)
        return r
    }

    /** 迁移时被标 migrated_possibly_modified 的导入笔刷：工作台显示"原件可能被旧版改写过"的提示 */
    fun possiblyModified(id: String): Boolean {
        val e = BrushStoreBootstrap.managedEntry(id) ?: return false
        return PresetSnapshotStore.source(e.id)?.optString("provenance") == LegacyBrushParamsMigrator.PROVENANCE_POSSIBLY_MODIFIED
    }

    @Synchronized
    fun clearCache() = cache.clear()
}
