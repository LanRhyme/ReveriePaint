/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import com.reverie.paint.core.KppHelper
import java.util.Locale

/**
 * ABR 导入预设快照构建器。
 *
 * 只做三件事：造一份最小的 kpp 骨架、让 [PresetPropertyMap] 把 [BrushParams] 相对骨架的差异写进去
 * （和用户编辑覆盖走同一张表、同一个写入函数）、把预览 PNG 换成带这份 XML 的 kpp。
 * 这里没有自己的键写入器；表里没有的键，ABR 也写不进去。
 */
internal object AbrSnapshotBuilder {

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun build(
        previewPng: ByteArray,
        presetName: String,
        params: BrushParams,
        tipScale: Double? = null,
        patternRef: ResourceStore.Ref? = null,
    ): ByteArray {
        if (previewPng.size < 8) return previewPng
        for (i in 0 until 8) {
            if (previewPng[i] != PNG_HEADER[i]) return previewPng
        }
        val skeleton = skeleton(presetName, params)
        val ctx = PresetContext.of(skeleton)
        // 图案只经内容寻址 Ref 绑定（下面 writeTexturePattern），不走表里的显示名控件
        val controls = PresetPropertyMap.diff(PresetPropertyMap.readParams(skeleton), params, ctx)
            .filterKeys { it != ControlId.TEXTURE_PATTERN }
        var xml = PresetPropertyMap.applyToXml(skeleton, controls, params)
        if (patternRef != null) xml = PresetPropertyMap.writeTexturePattern(xml, patternRef)
        // 文件笔尖的 scale 决定报告尺寸（见 formatScale）
        if (params.tipAsset.isNotBlank() && tipScale != null) {
            xml = KppXml.setBrushAttr(xml, "scale", formatScale(tipScale))
        }
        return KppHelper.replacePresetXml(previewPng, xml)
    }

    /**
     * `scale` 是引擎对文件笔尖报告尺寸的唯一决定量。Krita `libs/brush/kis_scaling_size_brush.cpp`：
     * `KisScalingSizeBrush::userEffectiveSize() = qMax(width(), height()) * scale()`，
     * `KisBrushBasedPaintOpSettings::paintOpSize()` 返回的正是它。所以文件笔尖写 `scale="1"` 等于
     * 把原始像素尺寸当作笔刷大小，ABR 声明的 diameter 会丢；导入时写 `diameter / max(w, h)` 才能让报告尺寸与 ABR 一致。
     */
    private fun formatScale(v: Double): String {
        if (!v.isFinite() || v <= 0.0) return "1"
        val rounded = String.format(Locale.US, "%.6f", v)
        return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** 只含"大小 + 自动笔尖"的骨架；其余一律由表按差异写入 */
    private fun skeleton(name: String, p: BrushParams): String {
        val d = BrushParams()
        val op = if (p.paintOpId.isBlank() || p.paintOpId == "defaultpaintop") "paintbrush" else p.paintOpId
        val size = KppXml.fmt(p.size)
        val def = KppXml.autoBrushDef(
            size = p.size,
            spacing = d.spacing,
            angleRad = 0.0,
            fade = d.fade,
            shape = d.tipShape,
            spikes = d.spikes,
            ratio = d.ratio,
            antiAliasing = d.antiAliasing,
        )
        return """<?xml version="1.0" encoding="UTF-8"?>
<Preset name="${esc(name)}" paintopid="$op">
  <param type="string" name="paintop"><![CDATA[$op]]></param>
  <param type="string" name="paintopSize"><![CDATA[$size]]></param>
  <param type="string" name="brush_definition"><![CDATA[$def]]></param>
</Preset>"""
    }
}
