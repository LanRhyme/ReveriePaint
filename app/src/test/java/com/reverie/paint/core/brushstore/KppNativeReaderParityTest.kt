/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.util.zip.Inflater

/**
 * C++（ReverieCoreBrush.cpp）和 Kotlin 各有一套 kpp 读法；这里守住"两边解析出的资源引用一致"，防止悄悄漂移。
 *
 * 诚实说明：JVM 里不能跑原生代码，所以这是**逐行移植 + 源码字面量守卫**，不是真正的交叉执行：
 * - [NativeModel] 把 C++ 的 PNG 文本块扫描（zTXt/tEXt/iTXt）、引用抽取（步骤 3 / 3.5 / 4）、
 *   loadSingleBrushResource 的查找顺序（含百分号解码、取文件名、不分大小写回退、先笔尖目录后图案目录）移植成 Kotlin；
 * - [cppSourceStillHasTheRegexesWeMirror] 断言这些正则的字面量仍原样出现在 .cpp 里——C++ 改了，这里就红，提醒同步移植；
 * - 覆盖不到：Qt 的 QFile/QDir 在真实文件系统上的行为、KoPattern/KisBrush 的加载、步骤 2（内嵌 base64 图案）、
 *   .gih/.abr 的按需加载。这些只能在设备上验证。
 */
class KppNativeReaderParityTest {
    private val tipName = "水彩 笔尖.gbr"
    private val patName = "纸张 纹理.png"

    private fun xml(tipAttr: String = tipName, patPath: String = "/storage/emulated/0/Pictures/$patName") =
        """<Preset name="中文纹理笔" paintopid="paintbrush">""" +
            """<param name="brush_definition" type="string"><![CDATA[<Brush type="auto_brush" filename="$tipAttr" spacing="0.1"/>]]></param>""" +
            """<param name="Texture/Pattern/PatternFileName" type="string"><![CDATA[$patPath]]></param>""" +
            """<param name="Texture/Pattern/Name" type="string"><![CDATA[$patName]]></param>""" +
            """</Preset>"""

    private fun kpp(xml: String): ByteArray = KppHelper.replacePresetXml(KppFixtures.bytes(KppFixtures.all.first().first), xml)

    // ---- C++ 的移植 -------------------------------------------------------------------------------------

    private object NativeModel {
        /** C++ 的 PNG 文本块扫描：关键字必须恰好是 "preset"；zTXt / tEXt / iTXt（含压缩）；UTF-8 解码 */
        fun presetText(png: ByteArray): String? {
            var idx = 8
            while (idx + 12 <= png.size) {
                val len = ((png[idx].toInt() and 255) shl 24) or ((png[idx + 1].toInt() and 255) shl 16) or
                    ((png[idx + 2].toInt() and 255) shl 8) or (png[idx + 3].toInt() and 255)
                val type = String(png, idx + 4, 4, Charsets.ISO_8859_1)
                if ((type == "zTXt" || type == "tEXt" || type == "iTXt") && idx + 8 + len <= png.size) {
                    val d = png.copyOfRange(idx + 8, idx + 8 + len)
                    var nul = 0
                    while (nul < len && d[nul] != 0.toByte()) nul++
                    if (nul == 6 && String(d, 0, 6, Charsets.ISO_8859_1) == "preset") {
                        return when {
                            type == "zTXt" && nul < len - 2 -> inflate(d, nul + 2, len)?.toString(Charsets.UTF_8)
                            type == "tEXt" -> String(d, nul + 1, len - (nul + 1), Charsets.UTF_8)
                            type == "iTXt" && nul + 3 <= len -> {
                                val flag = d[nul + 1].toInt() and 255
                                val method = d[nul + 2].toInt() and 255
                                var p = nul + 3
                                while (p < len && d[p] != 0.toByte()) p++
                                p++
                                while (p < len && d[p] != 0.toByte()) p++
                                p++
                                val textLen = len - p
                                when {
                                    textLen < 0 || p > len -> null
                                    flag == 1 && method == 0 -> inflate(d, p, len)?.toString(Charsets.UTF_8)
                                    flag == 0 -> String(d, p, textLen, Charsets.UTF_8)
                                    else -> null
                                }
                            }
                            else -> null
                        }
                    }
                }
                idx += 12 + len
            }
            return null
        }

        private fun inflate(b: ByteArray, from: Int, to: Int): ByteArray? {
            val inf = Inflater()
            inf.setInput(b, from, to - from)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        // 这三条与 ReverieCoreBrush.cpp 的字面量一致（由 cppSourceStillHasTheRegexesWeMirror 守卫）
        val ATTR = Regex("""(?:filename|pattern)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val REQ = Regex("""<param\b[^>]*?name=["']requiredBrushFiles?(?:List)?["'][^>]*>(?:<!\[CDATA\[)?([^<]+?)(?:\]\]>)?</param>""", RegexOption.IGNORE_CASE)
        val ANY_FILE = Regex("""([^"'<>\r\n\t]+?\.(?:gbr|gih|png|svg|pat|abr|jpg|jpeg))""", RegexOption.IGNORE_CASE)

        /** C++ 会拿去调 loadSingleBrushResource 的名字，按调用顺序（不含步骤 2：内嵌 base64 图案） */
        fun candidates(xml: String): List<String> =
            ATTR.findAll(xml).map { it.groupValues[1].trim() }.toList() +
                REQ.findAll(xml).map { it.groupValues[1].trim() }.toList() +
                ANY_FILE.findAll(xml).map { it.groupValues[1].trim() }.toList()

        /**
         * loadSingleBrushResource 的查找：baseName / 百分号解码后 / 只取文件名，依次先笔尖目录后图案目录，
         * 再不分大小写按文件名找。返回命中的 (目录, 文件名)，找不到为 null。
         */
        fun resolve(baseName: String, tips: Set<String>, patterns: Set<String>): Pair<String, String>? {
            val clean = percentDecode(baseName)
            val bare = clean.substringAfterLast('/')
            for (n in listOf(baseName, clean, bare)) if (n in tips) return "tips" to n
            for (n in listOf(baseName, clean, bare)) if (n in patterns) return "patterns" to n
            tips.firstOrNull { it.equals(bare, ignoreCase = true) }?.let { return "tips" to it }
            patterns.firstOrNull { it.equals(bare, ignoreCase = true) }?.let { return "patterns" to it }
            return null
        }

        // QUrl::fromPercentEncoding：只解码合法的 %XX，其余原样
        private fun percentDecode(s: String): String =
            if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)) s else runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }

    // ---- 测试 -------------------------------------------------------------------------------------------

    @Test
    fun `两边从同一个 kpp 读出同一份 preset XML（中文也不变）`() {
        val x = xml()
        val bytes = kpp(x)
        assertEquals(x, KppHelper.readPresetXml(bytes))
        assertEquals("C++ 的 PNG 文本块扫描读到的与 Kotlin 一致", x, NativeModel.presetText(bytes))
    }

    @Test
    fun `中文笔尖和带纹理的预设 C++ 解析出的引用能找到 Kotlin 读到的同一个文件`() {
        val x = xml()
        val bytes = kpp(x)
        val tips = setOf(tipName, "other.gbr")
        val patterns = setOf(patName)

        // Kotlin 读法
        val kotlinTip = requireNotNull(KppHelper.extractTipAssetFilename(bytes))
        assertEquals(tipName, kotlinTip)
        assertEquals(tipName, KppXml.brushAttr(x, "filename"))
        val kotlinPattern = PlaybackPresetCache.referencedPatternNames(bytes)
        assertEquals(listOf(patName), kotlinPattern)

        // C++ 读法：引用候选里必须能解析到那两个文件
        val cand = NativeModel.candidates(NativeModel.presetText(bytes)!!)
        val resolved = cand.mapNotNull { NativeModel.resolve(it, tips, patterns) }.toSet()
        assertTrue("笔尖 $tipName 没被 C++ 解析到：候选=$cand", "tips" to tipName in resolved)
        assertTrue("图案 $patName 没被 C++ 解析到：候选=$cand", "patterns" to patName in resolved)
        // 候选里的垃圾名字（带 CDATA 前缀等）都只会解析失败，不会解析成别的文件
        assertEquals(setOf("tips" to tipName, "patterns" to patName), resolved)
    }

    @Test
    fun `纹理引用是绝对路径时 C++ 取文件名与 Kotlin 的 File name 一致`() {
        val x = xml(patPath = "C:\\Users\\某人\\图案\\$patName".replace('\\', '/'))
        val bytes = kpp(x)
        val cand = NativeModel.candidates(NativeModel.presetText(bytes)!!)
        val hit = cand.mapNotNull { NativeModel.resolve(it, emptySet(), setOf(patName)) }
        assertEquals(listOf("patterns" to patName), hit.distinct())
        assertEquals(listOf(patName), PlaybackPresetCache.referencedPatternNames(bytes))
    }

    @Test
    fun `已知分歧：笔尖名里有单引号时 C++ 的引用扫描会截断名字 靠目录预加载兜底`() {
        // Kotlin 取整个属性值；C++ 的 ["'] 把单引号当成结束符，只拿到引号之前的部分
        val tricky = "老师's笔尖.gbr"
        val x = xml(tipAttr = tricky)
        val bytes = kpp(x)
        assertEquals(tricky, KppHelper.extractTipAssetFilename(bytes))
        val cand = NativeModel.candidates(NativeModel.presetText(bytes)!!)
        val resolved = cand.mapNotNull { NativeModel.resolve(it, setOf(tricky), emptySet()) }
        assertTrue("按引用扫描解析不到——只有 loadBrushResources 启动时扫整个目录（<16MB 的 gbr/png/svg）才会加载它", resolved.isEmpty())
    }

    @Test
    fun cppSourceStillHasTheRegexesWeMirror() {
        val cpp = listOf(File("src/main/cpp/ReverieCoreBrush.cpp"), File("app/src/main/cpp/ReverieCoreBrush.cpp")).first { it.isFile }.readText()
        fun has(s: String) = assertTrue("ReverieCoreBrush.cpp 里找不到 $s —— C++ 改了读法，请同步本测试的 NativeModel", cpp.contains(s))
        has("""(?:filename|pattern)\\s*=\\s*[\"']([^\"']+)[\"']""")
        has("""requiredBrushFiles?(?:List)?""")
        has("""([^\"'<>\r\n\t]+?\\.(?:gbr|gih|png|svg|pat|abr|jpg|jpeg))""")
        has("""QUrl::fromPercentEncoding(baseName.toUtf8())""")
        has("""QFileInfo(cleanName).fileName()""")
        has("""memcmp(chunkData, "preset", 6) == 0""")
        has("""QString::fromUtf8(decomp)""")
        // 查找顺序：先笔尖目录、再图案目录、最后不分大小写
        val brushIdx = cpp.indexOf("fullPath = testPath(m_brushDir, baseName)")
        val patIdx = cpp.indexOf("fullPath = testPath(m_patternDir, baseName)")
        val ciIdx = cpp.indexOf("findCaseInsensitive(m_brushDir, bareName)")
        assertTrue(brushIdx in 0 until patIdx && patIdx < ciIdx)
    }
}
