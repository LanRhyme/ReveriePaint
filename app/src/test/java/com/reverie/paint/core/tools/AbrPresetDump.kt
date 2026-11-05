/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.tools

import com.reverie.paint.core.AbrParser
import com.reverie.paint.core.AbrParser.ActionDescriptorValue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 把真实 ABR 里每个预设的完整描述符树逐键打印出来（Task C 证据脚本，不是回归测试）。
 * 入口：MEIKA_ABR=<abr 路径>；ABR_DUMP_OUT=<输出文件>；ABR_DUMP_NAMES=<逗号分隔的名字子串，空=只列名单>。
 * 没设 MEIKA_ABR 时跳过（样本太大且有版权，不入库）。
 */
class AbrPresetDump {
    private fun fmt(v: ActionDescriptorValue, ind: String, sb: StringBuilder, key: String) {
        when (v) {
            is ActionDescriptorValue.Descriptor -> {
                sb.appendLine("$ind$key: Desc(${v.classId}) {")
                for ((k, c) in v.items) fmt(c, "$ind  ", sb, k)
                sb.appendLine("$ind}")
            }
            is ActionDescriptorValue.ValueList -> {
                sb.appendLine("$ind$key: List[${v.list.size}]")
                v.list.take(3).forEachIndexed { i, c -> fmt(c, "$ind  ", sb, "[$i]") }
            }
            is ActionDescriptorValue.DoubleVal -> sb.appendLine("$ind$key: Doub ${v.value}")
            is ActionDescriptorValue.UnitFloatVal -> sb.appendLine("$ind$key: UntF ${v.value}${v.unit}")
            is ActionDescriptorValue.StringVal -> sb.appendLine("$ind$key: TEXT \"${v.value}\"")
            is ActionDescriptorValue.EnumVal -> sb.appendLine("$ind$key: enum ${v.typeId}:${v.enumValue}")
            is ActionDescriptorValue.IntVal -> sb.appendLine("$ind$key: long ${v.value}")
            is ActionDescriptorValue.LongVal -> sb.appendLine("$ind$key: comp ${v.value}")
            is ActionDescriptorValue.BoolVal -> sb.appendLine("$ind$key: bool ${v.value}")
            is ActionDescriptorValue.RawBytes -> sb.appendLine("$ind$key: tdta ${v.data.size}B")
        }
    }

    @Test
    fun dump() {
        val f = System.getenv("MEIKA_ABR")?.let(::File)
        assumeTrue("MEIKA_ABR 未设置", f != null && f.isFile)
        val root = AbrParser.readDescriptorRoot(f!!.readBytes())!!
        val brsh = root.items["Brsh"] as ActionDescriptorValue.ValueList
        val names = (System.getenv("ABR_DUMP_NAMES") ?: "").split(',').filter { it.isNotBlank() }
        val sb = StringBuilder()
        brsh.list.forEachIndexed { i, d ->
            val desc = d as? ActionDescriptorValue.Descriptor ?: return@forEachIndexed
            val nm = (desc.items["Nm  "] as? ActionDescriptorValue.StringVal)?.value ?: "?"
            sb.appendLine("#${i + 1}\t$nm")
        }
        brsh.list.forEachIndexed { i, d ->
            val desc = d as? ActionDescriptorValue.Descriptor ?: return@forEachIndexed
            val nm = (desc.items["Nm  "] as? ActionDescriptorValue.StringVal)?.value ?: "?"
            if (names.any { nm.contains(it) || "#${i + 1}" == it }) {
                sb.appendLine(); sb.appendLine("==== #${i + 1} $nm ====")
                for ((k, c) in desc.items) if (k != "Brsh" || true) fmt(c, "", sb, k)
            }
        }
        // ABR_DUMP_FLAGS=Rpt ,Wtdg,Nose,useColorDynamics → 列出这些布尔键为 true 的预设
        val flags = (System.getenv("ABR_DUMP_FLAGS") ?: "").split(',').filter { it.isNotEmpty() }
        if (flags.isNotEmpty()) {
            sb.appendLine(); sb.appendLine("## flags")
            for (flag in flags) {
                val hit = brsh.list.mapIndexedNotNull { i, d ->
                    val desc = d as? ActionDescriptorValue.Descriptor ?: return@mapIndexedNotNull null
                    val v = (desc.items[flag] as? ActionDescriptorValue.BoolVal)?.value == true
                    if (v) "#${i + 1} ${(desc.items["Nm  "] as? ActionDescriptorValue.StringVal)?.value}" else null
                }
                sb.appendLine("$flag -> ${hit.joinToString(" | ")}")
            }
        }
        System.getenv("ABR_DUMP_OUT")?.let { File(it).writeText(sb.toString(), Charsets.UTF_8) }
    }
}
