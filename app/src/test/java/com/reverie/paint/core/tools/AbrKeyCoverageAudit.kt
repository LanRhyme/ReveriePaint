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
 * ABR 描述符键覆盖审计（Task B 证据脚本）：
 *   "见到的键"（真实 ABR 的 desc 树里出现的每个 路径/键）  vs  "被解析器读取的键"（运行期记录 get/containsKey）。
 * 做法：把 desc 树里每个 Descriptor.items 换成带记录的 Map，再调用生产解析函数
 * [AbrParser.extractPresetsFromDescriptor]，跑完后差集 = 解析器从不读取（被忽略）的键。
 * 同时汇总混合模式相关键的取值分布（为 Task A 的映射表提供真实取值）。
 *
 * 入口：环境变量 ABR_AUDIT_FILES（冒号分隔的 .abr 路径）；输出写入 ABR_AUDIT_OUT（缺省不写文件）。
 * 未设置 ABR_AUDIT_FILES 时跳过（样本版权不入库）。
 */
class AbrKeyCoverageAudit {

    private class RecMap(
        private val path: String,
        private val inner: Map<String, ActionDescriptorValue>,
        val accessed: MutableSet<String>,
    ) : Map<String, ActionDescriptorValue> by inner {
        override fun get(key: String): ActionDescriptorValue? { accessed += key; return inner[key] }
        override fun containsKey(key: String): Boolean { accessed += key; return inner.containsKey(key) }
        fun path() = path
    }

    private data class Seen(var count: Int = 0, val types: MutableSet<String> = linkedSetOf(), val samples: MutableSet<String> = linkedSetOf(), val dist: MutableMap<String, Int> = sortedMapOf())

    private fun typeOf(v: ActionDescriptorValue): String = when (v) {
        is ActionDescriptorValue.Descriptor -> "Desc(${v.classId})"
        is ActionDescriptorValue.ValueList -> "List"
        is ActionDescriptorValue.DoubleVal -> "Doub"
        is ActionDescriptorValue.UnitFloatVal -> "UntF(${v.unit})"
        is ActionDescriptorValue.StringVal -> "TEXT"
        is ActionDescriptorValue.EnumVal -> "enum(${v.typeId})"
        is ActionDescriptorValue.IntVal -> "long"
        is ActionDescriptorValue.LongVal -> "comp"
        is ActionDescriptorValue.BoolVal -> "bool"
        is ActionDescriptorValue.RawBytes -> "tdta(${v.data.size}B)"
    }

    private fun sampleOf(v: ActionDescriptorValue): String = when (v) {
        is ActionDescriptorValue.Descriptor -> "{${v.items.size} keys}"
        is ActionDescriptorValue.ValueList -> "[${v.list.size}]"
        is ActionDescriptorValue.DoubleVal -> v.value.toString()
        is ActionDescriptorValue.UnitFloatVal -> "${v.value}${v.unit}"
        is ActionDescriptorValue.StringVal -> "\"${v.value.take(40)}\""
        is ActionDescriptorValue.EnumVal -> v.enumValue
        is ActionDescriptorValue.IntVal -> v.value.toString()
        is ActionDescriptorValue.LongVal -> v.value.toString()
        is ActionDescriptorValue.BoolVal -> v.value.toString()
        is ActionDescriptorValue.RawBytes -> "${v.data.size}B"
    }

    @Test
    fun audit() {
        val files = (System.getenv("ABR_AUDIT_FILES") ?: "").split(':').filter { it.isNotBlank() }.map(::File).filter { it.isFile }
        assumeTrue("ABR_AUDIT_FILES 未设置", files.isNotEmpty())
        val out = StringBuilder()
        val seen = sortedMapOf<String, Seen>()          // path/key -> stats
        val consumed = sortedSetOf<String>()            // path/key
        val modeValues = sortedMapOf<String, MutableMap<String, Int>>() // path/key -> value -> count
        val modeKeyRe = Regex("""(?i)^(md  |blnm|mode|textureBlendMode|bltn|.*blendmode.*)$""")

        fun wrap(path: String, v: ActionDescriptorValue, recs: MutableList<RecMap>): ActionDescriptorValue = when (v) {
            is ActionDescriptorValue.Descriptor -> {
                val acc = mutableSetOf<String>()
                for ((k, child) in v.items) {
                    val st = seen.getOrPut("$path/$k") { Seen() }
                    st.count++; st.types += typeOf(child); if (st.samples.size < 4) st.samples += sampleOf(child)
                    if (child !is ActionDescriptorValue.Descriptor && child !is ActionDescriptorValue.ValueList && child !is ActionDescriptorValue.RawBytes && child !is ActionDescriptorValue.StringVal && child !is ActionDescriptorValue.DoubleVal && child !is ActionDescriptorValue.UnitFloatVal) { val sv = sampleOf(child); st.dist[sv] = (st.dist[sv] ?: 0) + 1 }
                    if (modeKeyRe.matches(k) && child is ActionDescriptorValue.EnumVal) {
                        val m = modeValues.getOrPut("$path/$k") { sortedMapOf() }
                        val key = "${child.typeId}:${child.enumValue}"
                        m[key] = (m[key] ?: 0) + 1
                    }
                }
                val wrapped = v.items.mapValues { (k, child) -> wrap("$path/$k", child, recs) }
                val rm = RecMap(path, wrapped, acc)
                recs += rm
                v.copy(items = rm)
            }
            is ActionDescriptorValue.ValueList -> v.copy(list = v.list.map { wrap("$path[]", it, recs) })
            else -> v
        }

        for (f in files) {
            val bytes = f.readBytes()
            val root = AbrParser.readDescriptorRoot(bytes)
            if (root == null) {
                out.appendLine("# ${f.name}: 无 v6+ desc 描述符（v1/v2 或无 desc）——无键可审计")
                continue
            }
            val recs = mutableListOf<RecMap>()
            val acc = mutableSetOf<String>()
            val rootWrapped = wrap("", ActionDescriptorValue.Descriptor(root.name, root.classId, root.items), recs) as ActionDescriptorValue.Descriptor
            val node = AbrParser.ActionDescriptorNode(root.name, root.classId, rootWrapped.items)
            val presets = AbrParser.extractPresetsFromDescriptor(node, emptyList(), "AUDIT")
            for (r in recs) for (k in r.accessed) consumed += "${r.path()}/$k"
            out.appendLine("# ${f.name}: ${bytes.size} bytes, 解析出 ${presets.size} 个预设")
        }

        out.appendLine()
        out.appendLine("## 混合模式相关枚举取值（路径/键 -> typeId:值 x次数）")
        for ((p, m) in modeValues) out.appendLine("$p  ->  " + m.entries.joinToString(", ") { "${it.key} x${it.value}" })

        // 在父路径内被访问过但叶子未访问的键；容器（Desc/List）被读过则其子键单独判定
        val unread = seen.filterKeys { it !in consumed }
        out.appendLine()
        out.appendLine("## 见到但解析器从未读取的键（路径/键 | 出现次数 | 类型 | 样例）：共 ${unread.size} 个")
        for ((k, s) in unread) out.appendLine("$k | ${s.count} | ${s.types.joinToString("/")} | ${s.samples.joinToString(", ")}")
        out.appendLine()
        out.appendLine("## 离散取值分布（bool/long/enum 键：路径/键 [是否被读取] -> 值 x次数）")
        for ((k, s) in seen) if (s.dist.isNotEmpty() && s.dist.size <= 12) {
            out.appendLine("$k [${if (k in consumed) "read" else "UNREAD"}] -> " + s.dist.entries.joinToString(", ") { "${it.key} x${it.value}" })
        }
        out.appendLine()
        out.appendLine("## 见到且被读取的键：共 ${seen.size - unread.size} 个")
        for (k in seen.keys.filter { it in consumed }) out.appendLine("$k")

        System.getenv("ABR_AUDIT_OUT")?.let { File(it).writeText(out.toString()) }
        println(out)
    }
}
