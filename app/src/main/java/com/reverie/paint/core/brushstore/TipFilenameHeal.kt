/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import java.io.File

/**
 * 有效副本上：预设里的笔尖 `filename` 在磁盘上不存在、但 `md5sum` 能命中
 * [ResourceStore] 已落盘文件时，把 `filename` 改成磁盘文件名。快照字节不改。
 */
object TipFilenameHeal {
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    fun apply(kppBytes: ByteArray, tipDir: File, store: ResourceStore): ByteArray {
        val xml = KppHelper.readPresetXml(kppBytes, stripEmbeddedResources = false) ?: return kppBytes
        var x = xml
        var changed = false

        val mainFn = KppXml.brushAttr(x, "filename")
        val mainMd5 = KppXml.brushAttr(x, "md5sum")
        val mainRef = rewriteTarget(mainFn, mainMd5, tipDir, store)
        if (mainRef != null && mainFn != null) {
            val oldBare = bareName(mainFn)
            x = KppXml.setBrushAttr(x, "filename", mainRef.file)
            val req = KppXml.get(x, "requiredBrushFile")
            if (req == mainFn || req == oldBare) {
                x = KppXml.set(x, "requiredBrushFile", mainRef.file)
            }
            KppXml.get(x, "requiredBrushFilesList")?.let { list ->
                val rewritten = replaceListItems(list, mainFn, oldBare, mainRef.file)
                if (rewritten != list) x = KppXml.set(x, "requiredBrushFilesList", rewritten)
            }
            changed = true
        }

        val maskDef = KppXml.get(x, KppXml.MASK_BRUSH_DEF)
        if (maskDef != null) {
            val maskFn = KppXml.tagAttr(maskDef, "Brush", "filename")
            val maskMd5 = KppXml.tagAttr(maskDef, "Brush", "md5sum")
            val maskRef = rewriteTarget(maskFn, maskMd5, tipDir, store)
            if (maskRef != null) {
                x = KppXml.set(x, KppXml.MASK_BRUSH_DEF, KppXml.setTagAttr(maskDef, "Brush", "filename", maskRef.file))
                changed = true
            }
        }

        if (!changed || x == xml) return kppBytes
        return KppHelper.replacePresetXml(kppBytes, x)
    }

    private fun rewriteTarget(
        filename: String?,
        md5: String?,
        tipDir: File,
        store: ResourceStore,
    ): ResourceStore.Ref? {
        val fn = filename?.trim().orEmpty()
        val sum = md5?.trim().orEmpty()
        if (fn.isEmpty() || sum.isEmpty()) return null
        if (!HEX32.matches(sum)) return null
        val bare = bareName(fn)
        if (bare.isEmpty()) return null
        // XML 相对路径原样对应的文件已在磁盘上：不改名
        if (File(tipDir, fn).isFile) return null
        if (File(tipDir, bare).isFile) {
            val named = store.resolveByName(bare, ResourceStore.Kind.TIP) ?: return null
            if (!named.md5.equals(sum, ignoreCase = true)) return null
            if (named.file == fn) return null
            return named
        }
        val ref = store.resolve(sum, ResourceStore.Kind.TIP) ?: return null
        if (!store.fileFor(ref).isFile) return null
        if (ref.file == bare) return null
        return ref
    }

    private fun bareName(filename: String): String =
        filename.substringAfterLast('/').substringAfterLast('\\')

    /** 逗号/分号分隔；只替换整项等于旧 filename 或旧 bareName 的项，分隔符与空白保留。 */
    private fun replaceListItems(list: String, oldFull: String, oldBare: String, newName: String): String {
        val sb = StringBuilder(list.length + newName.length)
        var i = 0
        while (i < list.length) {
            val start = i
            while (i < list.length && list[i] != ',' && list[i] != ';') i++
            val token = list.substring(start, i)
            val trimmed = token.trim()
            if (trimmed == oldFull || trimmed == oldBare) {
                val lead = token.takeWhile { it.isWhitespace() }
                val trail = token.takeLastWhile { it.isWhitespace() }.let { t ->
                    if (t.length == token.length) "" else t
                }
                sb.append(lead).append(newName).append(trail)
            } else {
                sb.append(token)
            }
            if (i < list.length) {
                sb.append(list[i])
                i++
            }
        }
        return sb.toString()
    }
}
