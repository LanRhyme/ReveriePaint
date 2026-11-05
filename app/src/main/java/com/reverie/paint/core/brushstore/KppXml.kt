package com.reverie.paint.core.brushstore

/**
 * Krita 预设 XML 的最小读写层（W-03）。
 *
 * kpp 里参数有两种写法，都必须认：
 * - `<param type="string" name="X"><![CDATA[v]]></param>`
 * - `<param type="internal" name="X">v</param>`（name/type 顺序不定，旧文件甚至没有 type）
 *
 * 读按文档序**取最后一个**同名参数，与 KisPropertiesConfiguration::fromXML 的 setProperty
 * 覆盖语义一致；写入时第一个同名参数就地收敛为 CDATA 规范写法，其余重复键删除
 * （与 KppHelper.updateParam 相同），不存在则插到 `</Preset>` 前。
 */
object KppXml {
    private val cache = object : LinkedHashMap<String, Regex>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean = size > 256
    }

    fun paramPattern(name: String): Regex = synchronized(cache) {
        cache.getOrPut(name) {
            Regex("""<param\b[^>]*\bname="${Regex.escape(name)}"[^>]*>(.*?)</param>""", RegexOption.DOT_MATCHES_ALL)
        }
    }

    private fun decodeBody(body: String): String {
        val t = body
        val cdStart = t.indexOf("<![CDATA[")
        if (cdStart >= 0) {
            val cdEnd = t.lastIndexOf("]]>")
            if (cdEnd > cdStart) return t.substring(cdStart + 9, cdEnd)
        }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    /** 一个同名参数在文本中的位置：[start, end) 为整个 `<param ...>...</param>`，body 为内容区间 */
    private class Hit(val start: Int, val end: Int, val bodyStart: Int, val bodyEnd: Int)

    /**
     * 按文档序找出所有 `name="X"` 的 `<param>`。用 indexOf 代替整篇正则扫描（读值热路径，
     * 每个预设每次读值要查上百个键）；语义与 [paramPattern] 一致：name 属性必须位于
     * `<param` 开标签内（`<params` 不算），内容取到第一个 `</param>`。
     */
    private fun hits(xml: String, name: String): List<Hit> {
        val needle = "name=\"$name\""
        var from = 0
        var out: MutableList<Hit>? = null
        while (true) {
            val at = xml.indexOf(needle, from)
            if (at < 0) break
            from = at + needle.length
            if (at > 0 && !xml[at - 1].isWhitespace()) continue
            val lt = xml.lastIndexOf('<', at)
            if (lt < 0 || !xml.startsWith("<param", lt)) continue
            val after = lt + 6
            if (after < xml.length && (xml[after].isLetterOrDigit() || xml[after] == '_')) continue
            val gtBefore = xml.indexOf('>', lt)
            if (gtBefore in lt until at) continue
            val gt = xml.indexOf('>', from)
            if (gt < 0) break
            val close = xml.indexOf("</param>", gt + 1)
            if (close < 0) break
            (out ?: mutableListOf<Hit>().also { out = it }).add(Hit(lt, close + 8, gt + 1, close))
            from = close + 8
        }
        return out ?: emptyList()
    }

    fun has(xml: String, name: String): Boolean = hits(xml, name).isNotEmpty()

    fun get(xml: String, name: String): String? =
        hits(xml, name).lastOrNull()?.let { decodeBody(xml.substring(it.bodyStart, it.bodyEnd)) }

    fun getDouble(xml: String, name: String): Double? = get(xml, name)?.trim()?.toDoubleOrNull()

    fun getInt(xml: String, name: String): Int? = get(xml, name)?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    /** Krita QVariant::toBool 语义：true/1 为真，其余为假；缺省返回 [default] */
    fun getBool(xml: String, name: String, default: Boolean): Boolean {
        val v = get(xml, name)?.trim() ?: return default
        return v.equals("true", ignoreCase = true) || v == "1"
    }

    fun set(xml: String, name: String, value: String): String {
        val canonical = """<param type="string" name="$name"><![CDATA[$value]]></param>"""
        val matches = hits(xml, name)
        if (matches.isEmpty()) {
            val end = xml.lastIndexOf("</Preset>")
            return if (end >= 0) xml.substring(0, end) + " " + canonical + "\n" + xml.substring(end) else xml + canonical
        }
        val sb = StringBuilder(xml)
        for (i in matches.indices.reversed()) {
            val h = matches[i]
            sb.replace(h.start, h.end, if (i == 0) canonical else "")
        }
        return sb.toString()
    }

    fun remove(xml: String, name: String): String {
        val hs = hits(xml, name)
        if (hs.isEmpty()) return xml
        val sb = StringBuilder(xml)
        for (h in hs.asReversed()) sb.delete(h.start, h.end)
        return sb.toString()
    }

    fun fmt(v: Double): String = v.toString()

    // ---- brush_definition / MaskGenerator ----

    const val BRUSH_DEF = "brush_definition"
    const val MASK_BRUSH_DEF = "MaskingBrush/Preset/brush_definition"

    /** 主 brush_definition 的内容；参数名精确匹配，不会误中掩膜副本 */
    fun mainBrushDef(xml: String): String? = get(xml, BRUSH_DEF)

    fun setMainBrushDef(xml: String, def: String): String = set(xml, BRUSH_DEF, def)

    private fun tagPattern(tag: String) = Regex("""<$tag\b([^>]*?)(/?)>""")

    fun tagAttr(fragment: String, tag: String, attr: String): String? {
        val m = tagPattern(tag).find(fragment) ?: return null
        return Regex("""(?:^|\s)${Regex.escape(attr)}="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1)
    }

    fun hasTag(fragment: String, tag: String): Boolean = tagPattern(tag).containsMatchIn(fragment)

    fun setTagAttr(fragment: String, tag: String, attr: String, value: String): String {
        val m = tagPattern(tag).find(fragment) ?: return fragment
        val attrs = m.groupValues[1]
        val p = Regex("""(^|\s)${Regex.escape(attr)}="[^"]*"""")
        val hit = p.find(attrs)
        val newAttrs = if (hit != null) {
            attrs.replaceRange(hit.range, hit.groupValues[1] + "$attr=\"$value\"")
        } else {
            "$attrs $attr=\"$value\""
        }
        return fragment.replaceRange(m.range, "<$tag$newAttrs${m.groupValues[2]}>")
    }

    fun brushAttr(xml: String, attr: String): String? = mainBrushDef(xml)?.let { tagAttr(it, "Brush", attr) }

    fun maskGenAttr(xml: String, attr: String): String? = mainBrushDef(xml)?.let { tagAttr(it, "MaskGenerator", attr) }

    fun hasMaskGenerator(xml: String): Boolean = mainBrushDef(xml)?.let { hasTag(it, "MaskGenerator") } == true

    fun setBrushAttr(xml: String, attr: String, value: String): String {
        val def = mainBrushDef(xml) ?: return xml
        if (!hasTag(def, "Brush")) return xml
        return setMainBrushDef(xml, setTagAttr(def, "Brush", attr, value))
    }

    fun setMaskGenAttr(xml: String, attr: String, value: String): String {
        val def = mainBrushDef(xml) ?: return xml
        if (!hasTag(def, "MaskGenerator")) return xml
        return setMainBrushDef(xml, setTagAttr(def, "MaskGenerator", attr, value))
    }

    /** `<Preset ... paintopid="X">` */
    fun paintOpId(xml: String): String? =
        Regex("""<Preset\b[^>]*\bpaintopid="([^"]*)"""").find(xml)?.groupValues?.get(1)

    /** 生成统一的 auto_brush 笔尖定义（含 MaskGenerator） */
    fun autoBrushDef(
        size: Double,
        spacing: Double,
        angleRad: Double = 0.0,
        fade: Double = 1.0,
        shape: Int = 0,
        spikes: Int = 2,
        ratio: Double = 1.0,
        antiAliasing: Int = 1,
    ): String {
        val fFade = fmt(fade.coerceIn(0.0, 1.0))
        val shapeStr = if (shape == 1) "rect" else "circle"
        val gen = """<MaskGenerator diameter="${fmt(size)}" hfade="$fFade" vfade="$fFade" id="default" spikes="${spikes.coerceAtLeast(2)}" type="$shapeStr" ratio="${fmt(ratio)}" antialiasEdges="${if (antiAliasing > 0) 1 else 0}"/>"""
        return """<Brush scale="1" type="auto_brush" useAutoSpacing="0" BrushVersion="2" spacing="${fmt(spacing)}" angle="${fmt(angleRad)}" brushApplication="0"> $gen </Brush>"""
    }
}
